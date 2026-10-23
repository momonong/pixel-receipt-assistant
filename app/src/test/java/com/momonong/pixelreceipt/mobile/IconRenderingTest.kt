package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Draws the actual launcher vectors; no Compose, account, device or network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconRenderingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val output = File("../.receipt-lab/mobile-sync-20261022/icons")

    @Test fun launcherArtworkFitsAdaptiveMasksAndHasADedicatedThemedSilhouette() {
        val adaptive = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertEquals(Color.parseColor("#17675F"), context.getColor(R.color.launcher_background))
        assertEquals(R.drawable.ic_launcher_monochrome, monochromeResource())
        val colorArtwork = requireNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))
        val monochrome = requireNotNull(context.getDrawable(R.drawable.ic_launcher_monochrome))
        val colorPixels = renderLayer(colorArtwork)
        val monochromePixels = renderLayer(monochrome)
        assertInsideSafeZone(colorPixels)
        assertInsideSafeZone(monochromePixels)
        assertTrue("The color icon retains its cream receipt", countColor(colorPixels, "#FFF4DF") > 100)
        assertTrue("The color icon retains its mint check", countColor(colorPixels, "#B4E5C6") > 20)
        assertEquals("Themed detail lines must be transparent", 0, Color.alpha(monochromePixels.getPixel(44 * 4, 43 * 4)))
        assertTrue("Themed check remains visible", Color.alpha(monochromePixels.getPixel(71 * 4, 71 * 4)) > 200)

        val previews = linkedMapOf<String, Bitmap>()
        listOf("round", "squircle").forEach { mask ->
            previews["color-$mask"] = renderMasked(adaptive.background, colorArtwork, mask)
            previews["themed-light-$mask"] = renderMasked(ColorDrawable(Color.parseColor("#DDEDDD")),
                requireNotNull(context.getDrawable(R.drawable.ic_launcher_monochrome)).mutate().apply { setTint(Color.parseColor("#1B5149")) }, mask)
            previews["themed-dark-$mask"] = renderMasked(ColorDrawable(Color.parseColor("#203C35")),
                requireNotNull(context.getDrawable(R.drawable.ic_launcher_monochrome)).mutate().apply { setTint(Color.parseColor("#B4E5C6")) }, mask)
        }
        assertTrue(output.isDirectory || output.mkdirs())
        previews.forEach { (name, bitmap) ->
            assertEquals("Adaptive corners remain transparent", 0, Color.alpha(bitmap.getPixel(0, 0)))
            write(bitmap, "$name.png")
        }
        write(contactSheet(previews), "launcher-icon-sheet.png")
    }

    private fun monochromeResource(): Int {
        val xml = context.resources.getXml(R.mipmap.ic_launcher)
        try {
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "monochrome") {
                    return xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                }
            }
        } finally { xml.close() }
        fail("Adaptive icon must declare its themed silhouette")
        return 0
    }

    private fun renderLayer(drawable: Drawable): Bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { bitmap ->
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
    }

    private fun assertInsideSafeZone(bitmap: Bitmap) {
        var visible = 0
        val center = bitmap.width / 2f
        val radius = 33f * bitmap.width / 108f + 1f
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 16) {
                visible++
                val dx = x + 0.5f - center
                val dy = y + 0.5f - center
                assertTrue("Important artwork must remain inside the adaptive safe circle", dx * dx + dy * dy <= radius * radius)
            }
        }
        assertTrue("Launcher foreground must be visible", visible > 1000)
    }

    private fun countColor(bitmap: Bitmap, color: String): Int {
        val expected = Color.parseColor(color)
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == expected) count++
        return count
    }

    /** Crop the 108dp artwork to Android's central 72dp viewport with explicit launcher masks. */
    private fun renderMasked(background: Drawable, foreground: Drawable, mask: String): Bitmap =
        Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.scale(3f, 3f)
            canvas.translate(-18f, -18f)
            val clip = Path().apply {
                if (mask == "round") addCircle(54f, 54f, 36f, Path.Direction.CW)
                else {
                    moveTo(54f, 18f)
                    cubicTo(24f, 18f, 18f, 24f, 18f, 54f)
                    cubicTo(18f, 84f, 24f, 90f, 54f, 90f)
                    cubicTo(84f, 90f, 90f, 84f, 90f, 54f)
                    cubicTo(90f, 24f, 84f, 18f, 54f, 18f)
                    close()
                }
            }
            canvas.clipPath(clip)
            background.setBounds(0, 0, 108, 108)
            foreground.setBounds(0, 0, 108, 108)
            background.draw(canvas)
            foreground.draw(canvas)
        }

    private fun contactSheet(previews: Map<String, Bitmap>): Bitmap = Bitmap.createBitmap(720, 560, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#F3F5F2"))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#173D35"); textSize = 14f }
        previews.entries.forEachIndexed { index, (name, icon) ->
            val left = (index % 3) * 240f + 12f
            val top = (index / 3) * 280f + 12f
            canvas.drawBitmap(icon, left, top, null)
            canvas.drawText(name, left + 4f, top + 244f, text)
        }
    }

    private fun write(bitmap: Bitmap, name: String) {
        File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
