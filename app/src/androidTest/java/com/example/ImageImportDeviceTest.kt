package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.raw.RawImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun smallJpegImportsKeepAllEightExifTransforms() = runBlocking {
        val source = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, colours[(if (y < source.height / 2) 0 else 2) + (if (x < source.width / 2) 0 else 1)])
        }
        // Corner order: top left, top right, bottom left, bottom right.
        val expectedCorners = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2)
        )
        val file = File.createTempFile("device-orientation-", ".jpg", context.cacheDir)
        try {
            for (orientation in 1..8) {
                file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val decoded = RawImageDecoder.decodeImage(context, Uri.fromFile(file)).bitmap
                try {
                    assertEquals(if (orientation >= 5) 256 else 384, decoded.width)
                    assertEquals(if (orientation >= 5) 384 else 256, decoded.height)
                    val actualCorners = listOf(
                        decoded.getPixel(20, 20), decoded.getPixel(decoded.width - 20, 20),
                        decoded.getPixel(20, decoded.height - 20), decoded.getPixel(decoded.width - 20, decoded.height - 20)
                    )
                    actualCorners.forEachIndexed { corner, pixel ->
                        assertColourNear("Orientation $orientation corner $corner", colours[expectedCorners[orientation - 1][corner]], pixel, 3)
                    }
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            file.delete()
            source.recycle()
        }
    }

    @Test
    fun transparentPngHasConsistentOpaquePreviewAndJpegAtFullStrength() = runBlocking {
        val source = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            Canvas(this).drawRect(0f, 0f, 32f, 48f, Paint().apply { color = Color.argb(128, 255, 255, 255) })
        }
        val file = File.createTempFile("device-transparent-", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = RawImageDecoder.decodeImage(context, Uri.fromFile(file)).bitmap
            val base = FilmEngine.normalizeTo35mmResolution(decoded)
            val film = FilmEngine.applyFilmFilter(base, FilmPreset.PORTRA_400, grainIntensity = 0f)
            try {
                assertEquals(64, base.width)
                assertEquals(48, base.height)
                assertFalse("The working photo must be opaque before preview blending", base.hasAlpha())
                assertEquals(FilmEngine.PHOTO_MATTE_COLOR, base.getPixel(48, 24))
                assertEquals(255, Color.alpha(base.getPixel(16, 24)))
                val nearFull = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                Canvas(nearFull).apply {
                    drawBitmap(base, 0f, 0f, null)
                    drawBitmap(film, 0f, 0f, Paint().apply { alpha = 252 })
                }
                val nearFullJpeg = decodeJpeg(nearFull)
                val fullJpeg = decodeJpeg(film)
                try {
                    for (x in listOf(16, 48)) {
                        assertColourNear("99% and 100% at x=$x", fullJpeg.getPixel(x, 24), nearFullJpeg.getPixel(x, 24), 3)
                        assertColourNear("99% preview and JPEG at x=$x", nearFull.getPixel(x, 24), nearFullJpeg.getPixel(x, 24), 3)
                        assertColourNear("Full-strength preview and JPEG at x=$x", film.getPixel(x, 24), fullJpeg.getPixel(x, 24), 3)
                        assertEquals(255, Color.alpha(nearFull.getPixel(x, 24)))
                    }
                } finally {
                    nearFull.recycle()
                    nearFullJpeg.recycle()
                    fullJpeg.recycle()
                }
            } finally {
                film.recycle()
                if (base !== decoded) base.recycle()
                decoded.recycle()
            }
        } finally {
            file.delete()
            source.recycle()
        }
    }

    @Test
    fun packedRawSamplesCannotReplaceGenuineEmbeddedJpeg() = runBlocking {
        val previewSource = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(45, 120, 200)) }
        val previewBytes = jpegBytes(previewSource)
        previewSource.recycle()
        val expected = BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
        val file = File.createTempFile("device-packed-", ".dng", context.cacheDir)
        try {
            val sensorLength = 1024 * 1024 * 12 / 8
            val buffer = ByteBuffer.allocate(256 + sensorLength + previewBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x49, 0x49, 0x2a, 0)).putInt(8)
            buffer.putShort(1)
            tiffEntry(buffer, 0x014a, 4, 1, 32)
            buffer.position(32)
            buffer.putShort(11)
            tiffEntry(buffer, 0x0100, 4, 1, 1024)
            tiffEntry(buffer, 0x0101, 4, 1, 1024)
            for ((tag, value) in listOf(0x0102 to 12, 0x0103 to 1, 0x0106 to 32803)) {
                buffer.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0)
            }
            tiffEntry(buffer, 0x0111, 4, 1, 256)
            tiffEntry(buffer, 0x0117, 4, 1, sensorLength)
            buffer.putShort(0x828d.toShort()).putShort(3).putInt(2).putShort(2).putShort(2)
            buffer.putShort(0x828e.toShort()).putShort(1).putInt(4).put(byteArrayOf(0, 1, 1, 2))
            tiffEntry(buffer, 0xc61a, 4, 1, 1024)
            tiffEntry(buffer, 0xc61d, 4, 1, 4095)
            buffer.position(256)
            repeat(sensorLength) { buffer.put(0xff.toByte()) }
            buffer.put(previewBytes)
            file.writeBytes(buffer.array())

            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
            try {
                assertEquals(expected.width, result.bitmap.width)
                assertEquals(expected.height, result.bitmap.height)
                val expectedPixels = IntArray(expected.width * expected.height)
                val actualPixels = IntArray(result.bitmap.width * result.bitmap.height)
                expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
                result.bitmap.getPixels(actualPixels, 0, result.bitmap.width, 0, 0, result.bitmap.width, result.bitmap.height)
                assertArrayEquals(expectedPixels, actualPixels)
                assertFalse(result.metadata.isColorRecovered)
                assertTrue(result.metadata.processingNote.orEmpty().contains("embedded JPEG preview"))
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            file.delete()
            expected.recycle()
        }
    }

    private fun assertColourNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertEquals("$message red", Color.red(expected).toDouble(), Color.red(actual).toDouble(), tolerance.toDouble())
        assertEquals("$message green", Color.green(expected).toDouble(), Color.green(actual).toDouble(), tolerance.toDouble())
        assertEquals("$message blue", Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), tolerance.toDouble())
    }

    private fun jpegBytes(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        output.toByteArray()
    }

    private fun decodeJpeg(bitmap: Bitmap): Bitmap {
        val bytes = jpegBytes(bitmap)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun tiffEntry(buffer: ByteBuffer, tag: Int, type: Int, count: Int, value: Int) {
        buffer.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
    }
}
