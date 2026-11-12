package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MobileInsightPersistenceTest {
    @Test fun frozenWriteSurvivesRecreationAndCannotBeReadByAnotherAccount() {
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val store=InsightPendingStore(dir)
            val request=InsightWrite(UUID.randomUUID().toString(),"manual",null,false,"{\"amount_yuan\":300}")
            store.write("account-a",request)
            assertEquals(request,InsightPendingStore(dir).read("account-a"))
            assertNull(InsightPendingStore(dir).read("account-b"))
            assertEquals(request,InsightPendingStore(dir).read("account-a"))
            assertFalse(dir.walkTopDown().filter { it.isFile }.any { it.readText().contains("account-a") })
        } finally { dir.deleteRecursively() }
    }
}
