package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.film.CropSettings
import com.example.film.EditSettings
import com.example.film.FilmPreset
import com.example.film.PhotoRenderer
import com.example.raw.RawImageDecoder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic files only; these tests never touch a user's draft or gallery. */
@RunWith(AndroidJUnit4::class)
class HighResolutionExportDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun retainedSourceExportsFullResolutionAndTheSameCropWithExifOrientation() = runBlocking {
        val source = quadrants(3000, 2000)
        val file = write(source, Bitmap.CompressFormat.JPEG)
        source.recycle()
        var retained: File? = null
        var preview: Bitmap? = null
        var result: Bitmap? = null
        try {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val decoded = RawImageDecoder.decodeImage(context, Uri.fromFile(file), keepSource = true)
            retained = decoded.sourceFile
            preview = decoded.bitmap
            assertEquals(2000 to 3000, decoded.sourceWidth to decoded.sourceHeight)
            assertTrue(maxOf(decoded.bitmap.width, decoded.bitmap.height) <= 2560)
            assertTrue(retained!!.isFile)
            assertEquals(file.length(), retained!!.length())

            result = PhotoRenderer.renderSource(retained!!, EditSettings())
            assertEquals(2000 to 3000, result!!.width to result!!.height)
            val expectedCorners = listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN)
            val actualCorners = listOf(
                result!!.getPixel(40, 40), result!!.getPixel(result!!.width - 40, 40),
                result!!.getPixel(40, result!!.height - 40), result!!.getPixel(result!!.width - 40, result!!.height - 40)
            )
            for (i in actualCorners.indices) {
                assertEquals(Color.red(expectedCorners[i]).toDouble(), Color.red(actualCorners[i]).toDouble(), 3.0)
                assertEquals(Color.green(expectedCorners[i]).toDouble(), Color.green(actualCorners[i]).toDouble(), 3.0)
                assertEquals(Color.blue(expectedCorners[i]).toDouble(), Color.blue(actualCorners[i]).toDouble(), 3.0)
            }
            result!!.recycle()
            result = PhotoRenderer.renderSource(retained!!, EditSettings(), 2048)
            assertEquals(1365 to 2048, result!!.width to result!!.height)
            result!!.recycle()

            val crop = CropSettings(.1f, .1f, .9f, .9f, quarterTurns = 1, straightenDegrees = 6f)
            result = PhotoRenderer.renderSource(retained!!, EditSettings(preset = FilmPreset.GOLD_200, grainIntensity = 0f, crop = crop))
            assertEquals(PhotoRenderer.dimensions(2000, 3000, crop), result!!.width to result!!.height)
            assertFalse(result!!.hasAlpha())
            assertTrue("The imported original must remain untouched", file.isFile)
        } finally {
            if (result?.isRecycled == false) result?.recycle()
            preview?.recycle()
            retained?.delete()
            file.delete()
        }
    }

    @Test
    fun grainAndGlowHaveNoExportTileSeamsOnAndroid() = runBlocking {
        val source = quadrants(1100, 760)
        Canvas(source).drawRect(480f, 330f, 550f, 410f, Paint().apply { color = Color.WHITE })
        val file = write(source, Bitmap.CompressFormat.PNG)
        val edit = EditSettings(preset = FilmPreset.CINESTILL_800T, strength = .8f, grainIntensity = 1.4f,
            exposure = .2f, warmth = .1f, glowIntensity = .9f)
        var preview: Bitmap? = null
        var export: Bitmap? = null
        try {
            for (settings in listOf(edit, EditSettings(crop = CropSettings(.13f, .11f, .86f, .91f,
                quarterTurns = 3, straightenDegrees = 6f)))) {
                preview = PhotoRenderer.renderPreview(source, settings)
                export = PhotoRenderer.renderSource(file, settings)
                assertEquals(preview!!.width to preview!!.height, export!!.width to export!!.height)
                val expected = IntArray(preview!!.width * preview!!.height)
                val actual = IntArray(expected.size)
                preview!!.getPixels(expected, 0, preview!!.width, 0, 0, preview!!.width, preview!!.height)
                export!!.getPixels(actual, 0, export!!.width, 0, 0, export!!.width, export!!.height)
                if (settings.crop.straightenDegrees == 0f) assertArrayEquals(expected, actual)
                else assertTransformedPixels(expected, actual)
                preview!!.recycle(); export!!.recycle(); preview = null; export = null
            }
        } finally {
            preview?.recycle(); export?.recycle(); source.recycle(); file.delete()
        }
    }

    private fun assertTransformedPixels(expected: IntArray, actual: IntArray) {
        var differences = 0
        var maximumDelta = 0
        for (index in expected.indices) {
            if (expected[index] != actual[index]) differences++
            maximumDelta = maxOf(maximumDelta, kotlin.math.abs(Color.red(expected[index]) - Color.red(actual[index])),
                kotlin.math.abs(Color.green(expected[index]) - Color.green(actual[index])), kotlin.math.abs(Color.blue(expected[index]) - Color.blue(actual[index])))
        }
        // Region rebasing can move Skia's bilinear sample across one 1/16-pixel weight boundary.
        assertTrue("Transformed pixels: $differences differences", differences <= maxOf(1, expected.size / 10_000))
        assertTrue("Transformed pixels: maximum channel difference $maximumDelta", maximumDelta <= 16)
    }

    private fun quadrants(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint()
        for ((index, colour) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
            paint.color = colour
            val left = (index % 2) * width / 2f
            val top = (index / 2) * height / 2f
            canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
        }
        setHasAlpha(false)
    }

    private fun write(bitmap: Bitmap, format: Bitmap.CompressFormat): File = File.createTempFile(
        "device_export_source_", if (format == Bitmap.CompressFormat.JPEG) ".jpg" else ".png", context.cacheDir
    ).apply { outputStream().use { check(bitmap.compress(format, 100, it)) } }
}
