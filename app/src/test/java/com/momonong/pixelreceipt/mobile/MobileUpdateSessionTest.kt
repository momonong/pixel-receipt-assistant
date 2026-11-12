package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MobileUpdateSessionTest {
    @Test fun lateOldUpdate401CannotEraseTheNewLoginOrReceiptFile()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val dir=File(context.cacheDir,UUID.randomUUID().toString()).apply {mkdirs()}
        val release=AppRelease(100,"1.0.0",3,"a".repeat(64),"b".repeat(64))
        val entered=CompletableDeferred<Unit>();val respond=CompletableDeferred<Unit>()
        val api=object:MobileApi by FakeMobileApi() {
            override suspend fun appRelease(token:String)=release
            override suspend fun downloadAppUpdate(token:String,release:AppRelease,file:File,progress:(Long)->Unit) {
                assertEquals("prm_old",token);entered.complete(Unit);respond.await();throw MobileApiFailure(401)
            }
            override suspend fun login(deviceId:String,password:String)="prm_new"
        }
        val vault=MemoryVault().apply {save("prm_old")};val store=MobileStore(dir)
        store.create(UUID.randomUUID().toString(),"原收據必須保留")
        val original=File(dir,"state.json").readBytes()
        val repository=MobileRepository(store,ImageStore(File(dir,"images")),vault,api)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var auth=0
        try {
            val update=MobileUpdateController(scope,repository,UpdateHostFixture(File(dir,"updates")),12){auth++}
            update.check();update.awaitIdle();update.download();entered.await()
            repository.logout();repository.login("fixture-only")
            assertEquals("prm_new",vault.read())
            respond.complete(Unit);update.awaitIdle()
            assertEquals("prm_new",vault.read());assertEquals(0,auth)
            assertArrayEquals(original,File(dir,"state.json").readBytes())
        }finally {respond.complete(Unit);scope.cancel();dir.deleteRecursively()}
    }
}
