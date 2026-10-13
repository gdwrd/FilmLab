package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.example.raw.RawFormat
import com.example.raw.RawDecodeResult
import com.example.raw.RawImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RawImageDecoderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun unknownMetadataDoesNotInventACameraOrSensor() {
        val file = File.createTempFile("unknown_", ".arw", context.cacheDir)
        try {
            file.writeBytes(ByteArray(32))
            val metadata = RawImageDecoder.extractMetadataFromFile(file, "photo.arw", RawFormat.SONY_ARW)
            assertNull(metadata.make)
            assertNull(metadata.model)
            assertNull(metadata.sensorDescription)
            assertNull(metadata.colorSpace)
            assertEquals("Unknown camera", metadata.displayCameraName)
            assertEquals("", metadata.hudTelemetryLine)
            assertEquals("Unknown", metadata.aspectRatioLabel)
        } finally {
            file.delete()
        }
    }

    @Test
    fun leicaJpegRemainsAStandardImage() {
        val file = File.createTempFile("leica_", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpeg(Color.rgb(180, 90, 50)))
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Leica Camera AG")
                setAttribute(ExifInterface.TAG_MODEL, "Leica Q2")
                saveAttributes()
            }
            val metadata = RawImageDecoder.extractMetadataFromFile(file, file.name, RawFormat.STANDARD)
            assertTrue(metadata.isLeica)
            assertEquals("Leica Q2", metadata.displayCameraName)
            assertEquals("IMAGE", metadata.resolvedBadgeLabel)
            assertNull(metadata.sensorDescription)
            assertFalse(metadata.isColorRecovered)
        } finally {
            file.delete()
        }
    }

    @Test
    fun embeddedColourPreviewDoesNotClaimSensorRecovery() = runBlocking {
        val preview = jpeg(Color.rgb(45, 120, 200))
        val file = rawWithEmbeddedPreview(preview)
        try {
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
            assertSamePixels(BitmapFactory.decodeByteArray(preview, 0, preview.size), result.bitmap)
            assertFalse(result.metadata.isColorRecovered)
            assertTrue(result.metadata.processingNote!!.contains("embedded JPEG preview"))
            assertNull(result.metadata.sensorDescription)
        } finally {
            file.delete()
        }
    }

    @Test
    fun monochromePreviewPixelsArePreserved() = runBlocking {
        val preview = jpeg(Color.GRAY)
        val file = rawWithEmbeddedPreview(preview)
        try {
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
            assertSamePixels(BitmapFactory.decodeByteArray(preview, 0, preview.size), result.bitmap)
            assertFalse(result.metadata.isColorRecovered)
            assertTrue(result.metadata.processingNote!!.contains("RAW sensor data was not decoded"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun unsupportedSensorLayoutsCannotReplaceTheGenuinePreview() = runBlocking {
        val preview = jpeg(Color.rgb(45, 120, 200))
        val expected = BitmapFactory.decodeByteArray(preview, 0, preview.size)
        val files = listOf(
            sensorContainer(preview, bits = 16),
            sensorContainer(preview, bits = 16, pattern = byteArrayOf(2, 1, 1, 0)),
            sensorContainer(preview, bits = 12),
            sensorContainer(preview, bits = 16, truncated = true),
            sensorContainer(preview, bits = 16, order = ByteOrder.BIG_ENDIAN)
        )
        try {
            for (file in files) {
                val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
                assertSamePixels(expected, result.bitmap)
                assertFalse(result.metadata.isColorRecovered)
                assertTrue(result.metadata.processingNote!!.contains("embedded JPEG preview"))
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun invalidTiffWithoutADecodableImageFailsCleanly() = runBlocking {
        val file = File.createTempFile("broken_", ".dng", context.cacheDir)
        try {
            val buffer = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x49, 0x49, 0x2a, 0)).putInt(Int.MAX_VALUE)
            file.writeBytes(buffer.array())
            val error = runCatching { RawImageDecoder.decodeImage(context, Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue("Invalid RAW data must fail instead of creating a blank sensor image", error is IllegalStateException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun standardJpegOrientationIsAppliedOnce() = runBlocking {
        val file = File.createTempFile("rotated_", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpeg(Color.rgb(180, 90, 50)))
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
            assertEquals(600, result.bitmap.width)
            assertEquals(800, result.bitmap.height)
            assertEquals(800, result.metadata.originalWidth)
            assertEquals(600, result.metadata.originalHeight)
        } finally {
            file.delete()
        }
    }

    @Test
    fun smallStandardImagesKeepAllExifTransforms() = runBlocking {
        verifyAllExifTransforms()
    }

    @Test
    @Config(sdk = [27])
    fun bitmapFactoryFallbackAppliesAllExifTransforms() = runBlocking {
        verifyAllExifTransforms()
    }

    @Test
    fun retainedSourceKeepsFullAvailableDimensionsAndOrientation() = runBlocking {
        val source = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val file = File.createTempFile("large_source_", ".jpg", context.cacheDir)
        var retained: File? = null
        try {
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            source.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_TRANSPOSE.toString())
                saveAttributes()
            }
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file), keepSource = true)
            retained = result.sourceFile
            assertTrue(retained!!.isFile)
            assertEquals(2000, result.sourceWidth)
            assertEquals(3000, result.sourceHeight)
            assertTrue(maxOf(result.bitmap.width, result.bitmap.height) <= 2560)
            assertTrue("Retaining a source must not change the user's file", file.isFile)
        } finally {
            retained?.delete()
            file.delete()
            if (!source.isRecycled) source.recycle()
        }
    }

    @Test
    fun retainedRawSourceIsTheGenuinePreviewRatherThanTheSensorContainer() = runBlocking {
        val preview = jpeg(Color.rgb(20, 100, 160))
        val file = rawWithEmbeddedPreview(preview)
        var retained: File? = null
        try {
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file), keepSource = true)
            retained = result.sourceFile
            assertTrue(retained!!.isFile)
            assertEquals(800, result.sourceWidth)
            assertEquals(600, result.sourceHeight)
            assertSamePixels(BitmapFactory.decodeByteArray(preview, 0, preview.size), BitmapFactory.decodeFile(retained!!.absolutePath))
            assertEquals(0xff.toByte(), retained!!.readBytes()[0])
            assertFalse(result.metadata.isColorRecovered)
        } finally {
            retained?.delete()
            file.delete()
        }
    }

    @Test
    fun embeddedPreviewKeepsItsOwnOrientationWithConflictingOrMissingRawOrientation() = runBlocking {
        val preview = orientationJpeg(ExifInterface.ORIENTATION_ROTATE_90)
        val plain = BitmapFactory.decodeByteArray(preview, 0, preview.size)
        val expected = Bitmap.createBitmap(plain, 0, 0, plain.width, plain.height, Matrix().apply { setRotate(90f) }, true)
        try {
            for (outer in listOf(ExifInterface.ORIENTATION_ROTATE_180, null)) {
                val file = rawWithEmbeddedPreview(preview, outer)
                var result: RawDecodeResult? = null
                var reopened: RawDecodeResult? = null
                try {
                    if (outer != null) assertEquals(outer, ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
                    result = RawImageDecoder.decodeImage(context, Uri.fromFile(file), keepSource = true)
                    assertEquals(600 to 800, result.sourceWidth to result.sourceHeight)
                    assertSamePixels(expected, result.bitmap)
                    assertArrayEquals("A self-oriented JPEG must remain intact", preview, result.sourceFile!!.readBytes())
                    reopened = RawImageDecoder.decodeImage(context, Uri.fromFile(result.sourceFile))
                    assertSamePixels(expected, reopened.bitmap)
                } finally {
                    result?.bitmap?.recycle(); reopened?.bitmap?.recycle()
                    result?.sourceFile?.delete(); file.delete()
                }
            }
        } finally { plain.recycle(); expected.recycle() }
    }

    @Test
    fun embeddedPreviewWithoutItsOwnOrientationUsesTheRawOrientationOnce() = runBlocking {
        val preview = orientationJpeg(null)
        val plain = BitmapFactory.decodeByteArray(preview, 0, preview.size)
        val expected = Bitmap.createBitmap(plain, 0, 0, plain.width, plain.height, Matrix().apply { setRotate(90f) }, true)
        val file = rawWithEmbeddedPreview(preview, ExifInterface.ORIENTATION_ROTATE_90)
        var result: RawDecodeResult? = null
        var reopened: RawDecodeResult? = null
        try {
            assertEquals(6, ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
            result = RawImageDecoder.decodeImage(context, Uri.fromFile(file), keepSource = true)
            assertEquals(600 to 800, result.sourceWidth to result.sourceHeight)
            assertSamePixels(expected, result.bitmap)
            assertEquals(6, ExifInterface(result.sourceFile!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
            reopened = RawImageDecoder.decodeImage(context, Uri.fromFile(result.sourceFile))
            assertSamePixels(expected, reopened.bitmap)
        } finally {
            result?.bitmap?.recycle(); reopened?.bitmap?.recycle()
            result?.sourceFile?.delete(); file.delete(); plain.recycle(); expected.recycle()
        }
    }

    private fun orientationJpeg(orientation: Int?): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for ((index, colour) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
            paint.color = colour
            val left = (index % 2) * 400f
            val top = (index / 2) * 300f
            canvas.drawRect(left, top, left + 400f, top + 300f, paint)
        }
        val file = File.createTempFile("tagged_preview_", ".jpg", context.cacheDir)
        return try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            if (orientation != null) ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            file.readBytes()
        } finally { bitmap.recycle(); file.delete() }
    }

    private suspend fun verifyAllExifTransforms() {
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
        val file = File.createTempFile("orientation_", ".jpg", context.cacheDir)
        try {
            for (orientation in 1..8) {
                file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file)).bitmap
                assertEquals(if (orientation >= 5) 256 else 384, result.width)
                assertEquals(if (orientation >= 5) 384 else 256, result.height)
                val actual = listOf(
                    result.getPixel(20, 20), result.getPixel(result.width - 20, 20),
                    result.getPixel(20, result.height - 20), result.getPixel(result.width - 20, result.height - 20)
                )
                for ((index, pixel) in actual.withIndex()) {
                    val expected = colours[expectedCorners[orientation - 1][index]]
                    assertEquals("Orientation $orientation corner $index red", Color.red(expected).toDouble(), Color.red(pixel).toDouble(), 3.0)
                    assertEquals("Orientation $orientation corner $index green", Color.green(expected).toDouble(), Color.green(pixel).toDouble(), 3.0)
                    assertEquals("Orientation $orientation corner $index blue", Color.blue(expected).toDouble(), Color.blue(pixel).toDouble(), 3.0)
                }
            }
        } finally {
            file.delete()
            source.recycle()
        }
    }

    private fun sensorContainer(
        preview: ByteArray,
        bits: Int,
        pattern: ByteArray = byteArrayOf(0, 1, 1, 2),
        truncated: Boolean = false,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ): File {
        val sensorLength = 1024 * 1024 * bits / 8
        val storedLength = if (truncated) 20 else sensorLength
        val buffer = ByteBuffer.allocate(256 + storedLength + preview.size).order(order)
        val endian = if (order == ByteOrder.LITTLE_ENDIAN) 'I' else 'M'
        buffer.put(endian.code.toByte()).put(endian.code.toByte()).putShort(42).putInt(8)
        buffer.putShort(1)
        entry(buffer, 0x014a, 4, 1, 32)
        buffer.position(32)
        buffer.putShort(11)
        entry(buffer, 0x0100, 4, 1, 1024)
        entry(buffer, 0x0101, 4, 1, 1024)
        for ((tag, value) in listOf(0x0102 to bits, 0x0103 to 1, 0x0106 to 32803)) {
            buffer.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0)
        }
        entry(buffer, 0x0111, 4, 1, 256)
        entry(buffer, 0x0117, 4, 1, sensorLength)
        buffer.putShort(0x828d.toShort()).putShort(3).putInt(2).putShort(2).putShort(2)
        buffer.putShort(0x828e.toShort()).putShort(1).putInt(4).put(pattern)
        entry(buffer, 0xc61a, 4, 1, 1024)
        entry(buffer, 0xc61d, 4, 1, 4095)
        buffer.position(256)
        repeat(storedLength) { buffer.put(0xff.toByte()) }
        buffer.put(preview)
        return File.createTempFile("sensor_", ".dng", context.cacheDir).apply { writeBytes(buffer.array()) }
    }

    private fun entry(buffer: ByteBuffer, tag: Int, type: Int, count: Int, value: Int) {
        buffer.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
    }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(actual.width * actual.height)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(expectedPixels, actualPixels)
    }

    private fun rawWithEmbeddedPreview(preview: ByteArray, orientation: Int? = null): File {
        val header = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x49, 0x49, 0x2a, 0)).putInt(8)
        header.putShort((if (orientation == null) 1 else 2).toShort())
        if (orientation != null) {
            header.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        }
        entry(header, 0xc612, 1, 4, 0x00000401) // DNG version 1.4.
        header.putInt(0)
        return File.createTempFile("embedded_", ".dng", context.cacheDir).apply {
            outputStream().use { output -> output.write(header.array()); output.write(preview) }
        }
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
