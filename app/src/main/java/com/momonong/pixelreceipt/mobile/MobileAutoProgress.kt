package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun automaticProgressCandidate(batch: MobileBatch): Boolean {
    if (batch.deleted || batch.ledgerId != null || batch.ledgerStatus in setOf("active", "voided") || batch.draftStatus in setOf("confirmed", "voided")) return false
    return if (batch.intakeId != null) batch.status in setOf("queued", "preparing", "submitting", "processing", "unknown")
        else batch.syncRetryable && batch.frozen != null && batch.importComplete
}

/** One foreground loop. Known intake IDs only GET; unsent sends keep the durable original key/body. */
internal class MobileAutoProgress(
    private val scope: CoroutineScope,
    private val repository: MobileRepository,
    private val canSync: () -> Boolean,
    private val selected: () -> String?,
    private val availability: (Map<String, EngineAvailability>) -> Unit,
    private val unauthorized: () -> Unit,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var job: Job? = null
    private var foreground = false
    private var stopped = false
    private var generation = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var cursor = 0
    private var availabilityAt = 0L
    private val due = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    private val blocked = mutableSetOf<String>()
    private val enabledMutable = MutableStateFlow(false)
    val enabled = enabledMutable.asStateFlow()
    private val pausedMutable = MutableStateFlow<Set<String>>(emptySet())
    val paused = pausedMutable.asStateFlow()

    fun resume() { if (!foreground) { due.clear(); availabilityAt = 0 }; foreground = true; start(); wake.trySend(Unit) }
    fun background() { foreground = false; job?.cancel(); job = null; enabledMutable.value = false }
    fun stop() { stopped = true; job?.cancel(); job = null; enabledMutable.value = false }
    fun manualRetry() { stopped = false; due.clear(); blocked.clear(); pausedMutable.value = emptySet(); start(); wake.trySend(Unit) }
    fun sessionChanged() { due.clear(); blocked.clear(); pausedMutable.value = emptySet(); failures.clear(); availabilityAt = 0; stopped = false; start(); wake.trySend(Unit) }

    private fun start() {
        if (!foreground || stopped || job?.isActive == true) return
        val current = ++generation
        enabledMutable.value = true
        job = scope.launch {
            try {
                while (true) {
                    withTimeoutOrNull(5_000) { wake.receive() }
                    if (canSync()) tick()
                }
            } finally { if (generation == current) enabledMutable.value = false }
        }
    }

    private suspend fun tick() {
        val now = clock()
        val state = repository.store.state.value ?: return
        val candidates = state.batches.filter { batch ->
            automaticProgressCandidate(batch) && state.pendingMutation(batch.id) == null && batch.id !in blocked &&
                (batch.intakeId != null || (batch.syncRetryAt ?: 0) <= System.currentTimeMillis()) && (due[batch.id] ?: 0) <= now
        }
        if (candidates.isEmpty()) return
        if (now >= availabilityAt) {
            availabilityAt = now + 30_000
            try { availability(repository.availability()) }
            catch (error: Exception) {
                if (error is CancellationException && (error !is TimeoutCancellationException || !currentCoroutineContext().isActive)) throw error
                if (error is MobileApiFailure && error.statusCode == 401) { unauthorized(); return }
            }
        }
        val focused = candidates.firstOrNull { it.id == selected() }
        val others = candidates.filter { it.id != focused?.id }
        val count = minOf(if (focused == null) 8 else 7, others.size)
        val rotated = List(count) { others[(cursor + it) % others.size] }
        if (others.isNotEmpty()) cursor = (cursor + count) % others.size
        val chosen = listOfNotNull(focused) + rotated
        for (candidate in chosen) {
            if (!canSync()) return
            try {
                val current = repository.store.batch(candidate.id)
                if (current.deleted || (current.intakeId == null && !current.syncRetryable)) continue
                val result = repository.sync(current.id)
                failures.remove(current.id)
                val parked = result.serverJson?.let { runCatching { jsonObject(it).get("polling_finished")?.asBoolean == true }.getOrDefault(false) } == true
                due[current.id] = clock() + if (admissionWaiting(result) || parked) 30_000 else 5_000
            } catch (error: Exception) {
                if (error is CancellationException && (error !is TimeoutCancellationException || !currentCoroutineContext().isActive)) throw error
                if (error is MobileApiFailure && error.statusCode == 401) { unauthorized(); return }
                val count = ((failures[candidate.id] ?: 0) + 1).coerceAtMost(10)
                failures[candidate.id] = count
                due[candidate.id] = clock() + syncBackoffMillis(count)
                if (!retryableSyncFailure(error)) { blocked += candidate.id; pausedMutable.value = blocked.toSet() }
            }
        }
    }
}
