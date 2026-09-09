package com.momonong.pixelreceipt.data.extraction

import android.graphics.Bitmap

/** Read-only ink projection. ML Kit symbol boxes sometimes partition whitespace rather than fit ink. */
fun visibleColumnGaps(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): List<IntRange> {
    val width = right - left
    val height = bottom - top
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)
    val minimumGap = maxOf(4, height / 2)
    val gaps = mutableListOf<IntRange>()
    var start = -1
    for (x in 0 until width) {
        var ink = 0
        for (y in 0 until height) {
            val pixel = pixels[y * width + x]
            val luminance = 77 * ((pixel shr 16) and 255) + 150 * ((pixel shr 8) and 255) + 29 * (pixel and 255)
            if (luminance < 160 * 256) ink++
        }
        val blank = ink < maxOf(1, height / 20)
        if (blank && start < 0) start = x
        if (!blank && start >= 0) {
            if (x - start >= minimumGap) gaps += (left + start)..(left + x - 1)
            start = -1
        }
    }
    return gaps
}
