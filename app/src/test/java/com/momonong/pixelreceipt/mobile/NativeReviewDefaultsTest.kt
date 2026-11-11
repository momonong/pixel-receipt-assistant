package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeReviewDefaultsTest {
    private val today = LocalDate.of(2026, 11, 11)
    private fun draft(status: String = "matched", revision: Long = 1): JsonObject = jsonObject(nativeDraft(revision = revision)).apply {
        add("note_review", jsonObject("""{"status":"$status","decisions":[{"item_position":0,"purpose":"advance","note_quote":"牙膏兩條全部幫小美買"}],"unresolved_notes":[]}"""))
    }
    private fun saved(draft: JsonObject, decision: JsonObject = wholeDecision(0, "self")): JsonObject = draft.apply {
        addProperty("revision", 2)
        add("review_document", jsonObject("""{"schema":"pixelreceipt-central-review-2","assembly_status":"unknown","tolerance_acknowledgement":"none","line_decisions":[],"adjustment_decisions":[]}""").apply {
            add("line_decisions", JsonArray().apply { add(decision) })
        })
    }
    @Test fun missingDateDefaultsOnceWithoutRewritingReceiptFacts() {
        val source = draft().apply { getAsJsonObject("candidate").add("receipt_date", JsonNull.INSTANCE) }.toString()
        val review = createReview(source, today)
        assertEquals("2026-11-11", review.editor.getAsJsonObject("candidate").text("receipt_date"))
        assertTrue(review.draft.getAsJsonObject("candidate")["receipt_date"].isJsonNull)
        assertEquals(source, review.serverJson)
        val directory = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val store = MobileStore(directory)
            val batch = store.create(UUID.randomUUID().toString(), "")
            store.update(batch.id) { it.copy(review = review) }
            val recovered = MobileStore(directory).batch(batch.id).review!!
            assertEquals("2026-11-11", recovered.editor.getAsJsonObject("candidate").text("receipt_date"))
            assertEquals(review.editorJson, recovered.editorJson)
        } finally { directory.deleteRecursively() }
    }
    @Test fun savedBlankDatesAndPrintedDatesArePreserved() {
        val previous = saved(draft()).apply { getAsJsonObject("candidate").add("receipt_date", JsonNull.INSTANCE) }
        assertTrue(createReview(previous.toString(), today).editor.getAsJsonObject("candidate")["receipt_date"].isJsonNull)
        assertEquals("2026-10-23", createReview(draft().toString(), today).editor.getAsJsonObject("candidate").text("receipt_date"))
    }
    @Test fun freshMatchedNotesPopulatePurposesEvenPastRevisionOne() {
        val review = createReview(draft(revision = 2).toString(), today)
        assertEquals("advance", review.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        assertFalse(notesNeedAcknowledgement(review))
    }
    @Test fun freshMatchedGiftAndUnmentionedItemsHaveSeparateDefaults() {
        val remote = draft().apply {
            getAsJsonObject("note_review").array("decisions")[0].asJsonObject.addProperty("purpose", "gift")
            getAsJsonObject("candidate").array("items").add(getAsJsonObject("candidate").array("items")[0].deepCopy().asJsonObject.apply { addProperty("raw_name", "紅茶") })
        }
        val review = createReview(remote.toString(), today)
        assertEquals(listOf("gift", "self"), review.editor.array("line_decisions").map { it.asJsonObject.text("whole_purpose") })
    }
    @Test fun savedHumanPurposesRemainUntilExplicitApplyWhichPreservesMoneyAndFacts() {
        val review = createReview(saved(draft()).toString(), today)
        assertEquals("self", review.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        assertTrue(notesNeedAcknowledgement(review))
        assertTrue("notes" in prepareReview(review, complete = true).errors)
        val editor = review.editor
        val facts = editor["candidate"].deepCopy()
        assertTrue(applyNotePurposes(review, editor))
        val applied = review.copy(editorJson = editor.toString(), editRevision = 1)
        assertEquals(facts, applied.editor["candidate"])
        assertEquals("advance", applied.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        assertFalse(notesNeedAcknowledgement(applied))
    }
    @Test fun partialSplitsRequireExplicitKeepRatherThanReplacement() {
        val split = jsonObject("""{"item_position":0,"method":"explicit_amounts","whole_purpose":null,"quantities":{"self":1,"gift":0,"advance":1},"amounts_yuan":{"self":90,"gift":0,"advance":90}}""")
        val review = createReview(saved(draft(), split).toString(), today)
        val editor = review.editor
        assertFalse(applyNotePurposes(review, editor))
        assertEquals(split, editor.array("line_decisions")[0])
        assertTrue(notesNeedAcknowledgement(review))
        editor.addProperty("_notes_checked", "checked")
        val checked = review.copy(editorJson = editor.toString())
        assertFalse(notesNeedAcknowledgement(checked))
        assertNotNull(prepareReview(checked, complete = true).body)
    }
    @Test fun unresolvedOrStaleItemMappingNeedsAcknowledgementButAllowsDraftAutosave() {
        val review = createReview(draft(status = "needs_review").toString(), today)
        assertTrue(notesNeedAcknowledgement(review))
        assertNotNull(prepareReview(review).body)
        assertTrue("notes" in prepareReview(review, complete = true).errors)
        val stale = createReview(draft().toString(), today)
        val editor = stale.editor.apply { getAsJsonObject("candidate").array("items")[0].asJsonObject.addProperty("raw_name", "修正品名") }
        val changed = stale.copy(editorJson = editor.toString(), editRevision = 1)
        assertTrue(notesNeedAcknowledgement(changed))
        assertFalse(applyNotePurposes(changed, editor))
    }
    @Test fun checkedFlagIsDurableButNeverIncludedInReviewHttpBody() {
        val review = createReview(draft(status = "needs_review").toString(), today)
        val editor = review.editor.apply { addProperty("_notes_checked", "checked") }
        val checked = review.copy(editorJson = editor.toString())
        assertFalse(notesNeedAcknowledgement(checked))
        val body = jsonObject(prepareReview(checked, complete = true).body!!)
        assertFalse(body.has("_notes_checked"))
        assertFalse(body.entrySet().any { it.key.startsWith("_") })
        val handle = SavedStateHandle()
        val batch = MobileBatch(UUID.randomUUID().toString(), review = review)
        val edits = MobileReviewEdits(handle)
        edits.edit(batch) { it.addProperty("_notes_checked", "checked") }
        val recovered = MobileReviewEdits(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals("checked", recovered.state.value[batch.id]!!.value.editor.text("_notes_checked"))
    }
    @Test fun editingFactsOrPurposesInvalidatesPreviousNoteAcknowledgement() {
        val review = createReview(draft(status = "needs_review").toString(), today)
        val batch = MobileBatch(UUID.randomUUID().toString(), review = review)
        val edits = MobileReviewEdits(SavedStateHandle())
        edits.edit(batch) { it.addProperty("_notes_checked", "checked") }
        edits.edit(batch) { it.getAsJsonObject("candidate").array("items")[0].asJsonObject.addProperty("quantity", "3") }
        assertEquals("none", edits.state.value[batch.id]!!.value.editor.text("_notes_checked"))
        edits.edit(batch) { it.addProperty("_notes_checked", "checked") }
        edits.edit(batch) { it.array("line_decisions").set(0, wholeDecision(0, "gift")) }
        assertEquals("none", edits.state.value[batch.id]!!.value.editor.text("_notes_checked"))
    }
    @Test fun invalidDateErrorAsksForSelectionWithoutManualIsoInstructions() {
        val review = createReview(nativeDraft(), today)
        val editor = review.editor.apply { getAsJsonObject("candidate").addProperty("receipt_date", "2026-02-31") }
        assertEquals("請選擇有效日期。", prepareReview(review.copy(editorJson = editor.toString())).errors["receipt_date"])
    }
    @Test fun malformedMatchedProposalCannotSilentlyAcceptAllSelfDefaults() {
        val remote = draft().apply { getAsJsonObject("note_review").add("decisions", JsonArray()) }
        val review = createReview(remote.toString(), today)
        assertEquals("self", review.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        assertTrue(notesNeedAcknowledgement(review))
        assertTrue("notes" in prepareReview(review, complete = true).errors)
    }

}
