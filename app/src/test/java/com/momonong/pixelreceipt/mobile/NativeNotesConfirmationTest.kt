package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeNotesConfirmationTest {
    private fun notes() = jsonObject("""{"status":"needs_review","decisions":[],"unresolved_notes":["其中一項幫朋友買"]}""")
    @Test fun checkedNotesAreFrozenInConfirmationButNotInReviewBody() = runBlocking {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val store = MobileStore(dir); val api = NativeFixtureApi()
            api.remote = jsonObject(api.remote).apply { add("note_review", notes()) }.toString()
            val review = createReview(api.remote)
            val batch = store.create(UUID.randomUUID().toString(), "其中一項幫朋友買")
            store.update(batch.id) { it.copy(draftId = review.draft.text("id"), status = "succeeded", importComplete = true,
                review = review.copy(editorJson = review.editor.apply { addProperty("_notes_checked", "checked") }.toString())) }
            val repo = MobileRepository(store, ImageStore(File(dir, "images")), MemoryVault().apply { save("prm_fixture") }, api)
            repo.saveReview(batch.id); repo.confirmReview(batch.id)
            val attempt = api.confirmRequests.single()
            assertEquals("checked", jsonObject(attempt.body).text("notes_acknowledgement"))
            assertFalse(jsonObject(attempt.reviewBody).has("_notes_checked"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun knownNotesRejectionReopensEditingInsteadOfFreezingAnUnconfirmableRequest() = runBlocking {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val store = MobileStore(dir); val base = NativeFixtureApi()
            val api = object : MobileApi by base {
                override suspend fun confirm(token: String, id: String, attempt: FrozenConfirmation): String {
                    base.remote = jsonObject(base.remote).apply { add("note_review", notes()) }.toString()
                    throw MobileApiFailure(409, "notes_review_required")
                }
            }
            val review = createReview(base.remote)
            val batch = store.create(UUID.randomUUID().toString(), "其中一項幫朋友買")
            store.update(batch.id) { it.copy(draftId = review.draft.text("id"), status = "succeeded", importComplete = true, review = review) }
            val repo = MobileRepository(store, ImageStore(File(dir, "images")), MemoryVault().apply { save("prm_fixture") }, api)
            repo.saveReview(batch.id)
            try { repo.confirmReview(batch.id); fail() } catch (_: MobileApiFailure) { }
            val recovered = MobileStore(dir).batch(batch.id).review!!
            assertNull(recovered.confirmation)
            assertFalse(recovered.confirmationDispatched)
            assertTrue(notesNeedAcknowledgement(recovered))
            assertTrue(recovered.error!!.contains("敘述"))
            assertNull(base.entry)
        } finally { dir.deleteRecursively() }
    }
}
