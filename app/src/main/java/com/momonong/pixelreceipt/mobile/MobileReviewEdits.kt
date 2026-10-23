package com.momonong.pixelreceipt.mobile

import androidx.lifecycle.SavedStateHandle
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReviewEdit(val id: String, val value: MobileReview, val expectedRevision: Long, val error: String? = null)
/** Immediate UI/SavedState overlay; AtomicFile acknowledgement is required before any network mutation. */
internal class MobileReviewEdits(private val saved: SavedStateHandle) {
    private val gson = Gson()
    private val mutable = MutableStateFlow(saved.get<String>(KEY)?.let { json -> gson.fromJson(json, Array<ReviewEdit>::class.java).associate { it.id to it.copy(error = "已恢復目前編輯，請重試保存。") } }.orEmpty())
    val state = mutable.asStateFlow()
    private fun publish(value: Map<String, ReviewEdit>) { saved[KEY] = gson.toJson(value.values.toTypedArray()); mutable.value = value }
    fun edit(batch: MobileBatch, change: (com.google.gson.JsonObject) -> Unit) {
        val prior = mutable.value[batch.id]
        val review = prior?.value ?: batch.review ?: return
        if (review.locked) return
        val editor = review.editor.also(change)
        publish(mutable.value + (batch.id to ReviewEdit(batch.id, review.copy(editorJson = editor.toString(), editRevision = review.editRevision + 1, error = null), prior?.expectedRevision ?: review.editRevision, prior?.error)))
    }
    fun pending(id: String) = id in mutable.value
    fun overlay(state: MobileState?): MobileState? = state?.copy(batches = state.batches.map { batch ->
        val patch = mutable.value[batch.id]
        if (patch == null) batch else batch.copy(review = (batch.review ?: patch.value).copy(editorJson = patch.value.editorJson, editRevision = patch.value.editRevision, error = patch.error ?: batch.review?.error))
    })
    suspend fun persist(id: String, retry: Boolean = false, write: suspend (ReviewEdit) -> MobileBatch) {
        val snapshot = mutable.value[id] ?: return
        if (snapshot.error != null && !retry) return
        try {
            val stored = write(snapshot)
            val latest = mutable.value[id] ?: return
            publish(if (latest.value.editRevision == snapshot.value.editRevision) mutable.value - id else mutable.value +
                (id to latest.copy(expectedRevision = stored.review!!.editRevision, error = null)))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val latest = mutable.value[id] ?: return
            publish(mutable.value + (id to latest.copy(error = "尚未保存：${mobileError(error)}")))
        }
    }
    companion object { private const val KEY = "nativeReviewEdits" }
}
