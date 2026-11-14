package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileAutoProgressQueueTest {
    private lateinit var root: File
    private lateinit var store: MobileStore
    private lateinit var repo: MobileRepository
    private val scope=CoroutineScope(Dispatchers.Main+SupervisorJob())
    private val reads=CopyOnWriteArrayList<String>()
    private var now=System.currentTimeMillis()
    @Volatile private var waiting=false
    @Volatile private var denied=false
    private var invalidated=false
    private val models=ViewModelStore()
    @Volatile private var statusTimeout=false
    @Volatile private var availabilityTimeout=false
    @Before fun setup() {
        root=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        store=MobileStore(File(root,"state"));store.load()
        val api=object:MobileApi by FakeMobileApi(){
            override suspend fun recognition(token:String):Map<String,EngineAvailability>{
                if(availabilityTimeout){availabilityTimeout=false;withTimeout(1){delay(50)}}
                return mapOf("broker" to EngineAvailability(true,null))
            }
            override suspend fun status(token:String,id:String):ServerIntake {
                reads+=id
                if(statusTimeout){statusTimeout=false;withTimeout(1){delay(50)}}
                if(denied)throw MobileApiFailure(401)
                return ServerIntake(id,"queued",null,"""{"error_code":${if(waiting) "\"broker_admission_waiting\"" else "null"},"polling_finished":false}""")
            }
        }
        repo=MobileRepository(store,ImageStore(File(root,"images")),MemoryVault().apply{save("prm_fixture")},api)
    }
    @After fun cleanup(){models.clear();scope.cancel();root.deleteRecursively()}
    private fun batch():MobileBatch {
        val b=store.create(UUID.randomUUID().toString(),"保留說明")
        return store.update(b.id){it.copy(importComplete=true,status="queued",intakeId=UUID.randomUUID().toString())}
    }
    private fun controller(selected:String?=null)=MobileAutoProgress(scope,repo,{!invalidated},{selected},{},{invalidated=true},{now})
    private fun advance(seconds:Long){now+=seconds*1000;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))}
    private fun settle(){repeat(30){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}}
    @Test fun selectedReceiptCannotStarveTheLastOfSixteenWaitingReceipts(){
        val batches=List(16){batch()};val c=controller(batches.first().id);c.resume()
        repeat(4){advance(5);settle()}
        assertEquals("Every pending intake must be observed",batches.map{it.intakeId}.toSet(),reads.toSet())
    }
    @Test fun pausedAdmissionUsesSlowerPollingAndBackgroundMakesNoRequests(){
        waiting=true;batch();val c=controller();c.resume();advance(5);settle();assertEquals(1,reads.size)
        repeat(4){advance(5);settle()};assertEquals(1,reads.size)
        advance(11);settle();assertEquals(2,reads.size)
        c.background();advance(120);settle();assertEquals(2,reads.size)
        c.resume();advance(5);settle();assertEquals(3,reads.size)
    }
    @Test fun loginExpiryStopsTheRestOfTheQueue(){
        denied=true;repeat(4){batch()};val c=controller();c.resume();advance(5);settle()
        assertTrue(invalidated);assertEquals(1,reads.size)
        advance(90);settle();assertEquals(1,reads.size)
    }
    @Test fun statusTimeoutDoesNotKillTheAutomaticLoop(){
        batch();statusTimeout=true;val c=controller();c.resume();advance(5);settle();assertEquals(1,reads.size)
        advance(15);settle();assertEquals(2,reads.size);assertTrue(c.enabled.value)
    }
    @Test fun availabilityTimeoutDoesNotKillSavedIntakeTracking(){
        batch();availabilityTimeout=true;val c=controller();c.resume();advance(5);settle()
        advance(15);settle();assertTrue(reads.isNotEmpty());assertTrue(c.enabled.value)
    }
    @Test fun foregroundUsesOneBoundedNonBlockingQueueInsteadOfNineBusyReads(){
        val batches=List(16){batch()}
        val model=MobileViewModel(ApplicationProvider.getApplicationContext(),SavedStateHandle(mapOf("mobileSelected" to batches.last().id)),repo)
        models.put("model",model)
        var waits=0;while(!model.signedIn.value&&waits++<30)settle()
        assertTrue(model.signedIn.value)
        model.foreground();assertNull("Progress refresh must not disable the whole screen",model.busy.value)
        settle()
        assertTrue(reads.isNotEmpty());assertTrue(reads.size<=8)
        assertTrue(reads.contains(batches.last().intakeId))
    }
}
