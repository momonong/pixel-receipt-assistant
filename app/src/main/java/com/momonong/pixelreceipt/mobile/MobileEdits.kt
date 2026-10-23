package com.momonong.pixelreceipt.mobile

import androidx.lifecycle.SavedStateHandle
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MobileEdit(
    val batch: MobileBatch,
    val editRevision: Long,
    val expectedRevision: Long,
    val error: String? = null,
)

/** Owned by the ViewModel: text is never kept in a separate Compose-only draft. */
internal class MobileEdits(private val saved: SavedStateHandle) {
    private val gson = Gson()
    private val mutable = MutableStateFlow(
        saved.get<String>(KEY)?.let { json ->
            gson.fromJson(json, Array<MobileEdit>::class.java).associate { edit ->
                edit.batch.id to edit.copy(error = "尚未保存：已恢復編輯內容，請重試儲存後再送出。")
            }
        }.orEmpty(),
    )
    val state = mutable.asStateFlow()

    private fun publish(edits: Map<String, MobileEdit>) {
        saved[KEY] = gson.toJson(edits.values.toTypedArray())
        mutable.value = edits
    }

    fun edit(persisted: MobileBatch, change: (MobileBatch) -> MobileBatch) {
        val previous = mutable.value[persisted.id]
        val old = previous?.batch ?: persisted
        if (!old.editable) return
        val next = requireAssociationReview(old, change(old))
        require(next.id == old.id)
        publish(mutable.value + (old.id to MobileEdit(next, (previous?.editRevision ?: 0) + 1,
            previous?.expectedRevision ?: persisted.revision, previous?.error)))
    }

    fun overlay(state: MobileState?): MobileState? = state?.copy(batches = state.batches.map { mutable.value[it.id]?.batch ?: it })
    fun pending(id: String): Boolean = id in mutable.value

    /** Failed writes are sticky. Only the explicit retry action can attempt them again. */
    suspend fun persist(id: String, retry: Boolean = false, write: suspend (MobileBatch, Long) -> MobileBatch) {
        val snapshot = mutable.value[id] ?: return
        if (snapshot.error != null && !retry) return
        try {
            val stored = write(snapshot.batch, snapshot.expectedRevision)
            val latest = mutable.value[id] ?: return
            publish(if (latest.editRevision == snapshot.editRevision) mutable.value - id
                else mutable.value + (id to latest.copy(expectedRevision = stored.revision, error = null)))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val latest = mutable.value[id] ?: return
            publish(mutable.value + (id to latest.copy(error = "尚未保存：${mobileError(error)} 請重試儲存後再送出。")))
        }
    }

    companion object { private const val KEY = "mobileEdits" }
}
