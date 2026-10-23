package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Enqueue synchronously at the UI event boundary; IO dispatch cannot reorder notes and send. */
internal class MobileCommands(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    fun launch(action: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock { action() }
    }
}
