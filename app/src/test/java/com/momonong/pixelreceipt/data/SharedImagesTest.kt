package com.momonong.pixelreceipt.data

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.momonong.pixelreceipt.data.ingestion.SharedImages
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SharedImagesTest {
    private val first = Uri.parse("content://test/one")
    private val second = Uri.parse("content://test/two")
    @Test fun singleAndMultipleSharesPreserveOrderAndDoNotDoubleCountClipData() {
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first).apply {
            clipData = ClipData.newRawUri("image", first)
        }
        assertEquals(listOf(first), SharedImages.from(single))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        assertEquals(listOf(first, second), SharedImages.from(multiple))
    }
    @Test fun clipDataFallbackEmptyShareAndLauncherAreHandled() {
        val clip = ClipData.newRawUri("image", first).apply { addItem(ClipData.Item(second)) }
        assertEquals(listOf(first, second), SharedImages.from(Intent(Intent.ACTION_SEND_MULTIPLE).apply { clipData = clip }))
        assertEquals(emptyList<Uri>(), SharedImages.from(Intent(Intent.ACTION_SEND)))
        assertNull(SharedImages.from(Intent(Intent.ACTION_MAIN)))
    }
    @Test fun oversizedShareCarriesOverflowSentinel() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(List(100) { first }))
        assertEquals(21, SharedImages.from(intent)!!.size)
    }
}
