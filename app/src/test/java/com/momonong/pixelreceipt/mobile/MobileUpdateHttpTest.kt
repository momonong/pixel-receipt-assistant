package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class MobileUpdateHttpTest {
    private val bytes="verified-synthetic-apk".toByteArray()
    private val release=AppRelease(100,"1.0.0",bytes.size.toLong(),sha256(bytes),"b".repeat(64))
    @Test fun apkGetUsesOnlyFixedOriginSessionAndNoRedirects()=runBlocking {
        val dir=Files.createTempDirectory("apk-redirect").toFile();var calls=0
        val connection=StubConnection(302)
        try {
            val download=HttpsApkDownload { url -> calls++;assertEquals("https://momonong.me/receipts/v1/mobile/apk",url.toString());connection }
            try {download.download("prm_test",release,File(dir,"update.apk"),{});fail()} catch(error:MobileApiFailure){assertEquals(302,error.statusCode)}
            assertEquals(1,calls);assertFalse(connection.instanceFollowRedirects)
            assertEquals("Bearer prm_test",connection.getRequestProperty("Authorization"))
            assertEquals("identity",connection.getRequestProperty("Accept-Encoding"))
            assertTrue(connection.disconnected);assertFalse(File(dir,"update.apk").exists())
        } finally {dir.deleteRecursively()}
    }
    @Test fun apkTransportStreamsExactBytesAndRejectsChangedReleaseLength()=runBlocking {
        val dir=Files.createTempDirectory("apk-stream").toFile();val file=File(dir,"update.apk")
        val connection=object:StubConnection(200){override fun getInputStream()=ByteArrayInputStream(bytes);override fun getContentLengthLong()=bytes.size.toLong()}
        try {
            HttpsApkDownload {connection}.download("prm_test",release,file,{})
            assertArrayEquals(bytes,file.readBytes())
            val changed=object:StubConnection(200){override fun getContentLengthLong()=bytes.size+1L}
            try {HttpsApkDownload {changed}.download("prm_test",release,file,{});fail()}catch(_:java.io.IOException){}
            assertArrayEquals(bytes,file.readBytes());assertTrue(changed.disconnected)
        }finally {dir.deleteRecursively()}
    }
    @Test fun cancellationDisconnectsTheSocketAndCannotProduceAnApk()=runBlocking {
        val dir=Files.createTempDirectory("apk-socket-cancel").toFile();val entered=CompletableDeferred<Unit>();val stop=CountDownLatch(1)
        val connection=object:StubConnection(200){override fun getResponseCode():Int {entered.complete(Unit);stop.await();return 200};override fun disconnect(){super.disconnect();stop.countDown()}}
        try {
            val job=launch {HttpsApkDownload {connection}.download("prm_test",release,File(dir,"update.apk"),{})}
            withTimeout(3000){entered.await()};job.cancelAndJoin()
            assertTrue(connection.disconnected);assertFalse(File(dir,"update.apk").exists())
        }finally {stop.countDown();dir.deleteRecursively()}
    }
}
