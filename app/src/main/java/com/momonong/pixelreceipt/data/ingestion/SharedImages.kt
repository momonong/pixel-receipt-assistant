package com.momonong.pixelreceipt.data.ingestion

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Only reads image references. Ignores all caller-supplied draft ids, paths and operation ids. */
object SharedImages {
    fun from(intent: Intent): List<Uri>? {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }.ifEmpty {
            intent.clipData?.let { clip ->
                (0 until minOf(clip.itemCount, ImageStore.MAX_IMAGES + 1)).mapNotNull { clip.getItemAt(it).uri }
            }.orEmpty()
        }.take(ImageStore.MAX_IMAGES + 1) // Sentinel overflow rejects the whole batch, never silently truncates to 20.
    }
}
