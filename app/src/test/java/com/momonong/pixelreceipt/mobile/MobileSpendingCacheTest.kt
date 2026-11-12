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
class MobileSpendingCacheTest {
    @Test fun savingDerivedAnalysisReloadsTheLatestReceiptEditsUnderTheExistingFileLock(){
        val root=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val receipts=File(root,"receipts");val cacheFile=MobileSpendingCacheFile(File(root,"analysis"));val first=MobileStore(receipts);first.load();val id=UUID.randomUUID().toString();first.create(id,"原來合成說明")
            val marker=spendingMarker(first.state.value!!)
            val other=MobileStore(receipts);other.load();other.update(id){it.copy(userNotes="剛剛更新的合成說明")}
            val cache=MobileSpendingCache("v1/spending?bucket=auto","{}",2,marker)
            val before=File(receipts,"state.json").readBytes()
            val current={spendingMarker(MobileStore(receipts).load())}
            assertTrue(cacheFile.write(cache,current){true})
            val loaded=MobileStore(receipts).load();assertEquals("剛剛更新的合成說明",loaded.batches.single().userNotes)
            assertEquals(cache,cacheFile.read())
            assertFalse(cacheFile.write(cache.copy(marker="wrong-ledger-marker"),current){true})
            assertFalse(cacheFile.write(cache,current){false})
            assertEquals(loaded,MobileStore(receipts).load())
            assertArrayEquals(before,File(receipts,"state.json").readBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun anOversizedCacheCannotConsumeTheReceiptIndexCapacityOrMakeItUnwritable(){
        val root=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val receipts=File(root,"receipts");val store=MobileStore(receipts);val original=store.load();val marker=spendingMarker(original);val cacheFile=MobileSpendingCacheFile(File(root,"analysis"))
            assertFalse(cacheFile.write(MobileSpendingCache("v1/spending?bucket=auto","x".repeat(600*1024),2,marker),{marker}){true})
            assertTrue(store.writable.value);assertEquals(original,MobileStore(receipts).load())
        }finally{root.deleteRecursively()}
    }
}
