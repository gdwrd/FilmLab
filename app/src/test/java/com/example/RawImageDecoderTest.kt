package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.example.raw.DngColorExtractor
import com.example.raw.RawColorRecoveryEngine
import com.example.raw.RawFormat
import com.example.raw.RawImageDecoder
import com.example.raw.RawMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val file = rawWithEmbeddedPreview(Color.rgb(45, 120, 200))
        try {
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
            assertEquals(800, result.bitmap.width)
            assertFalse(result.metadata.isColorRecovered)
            assertTrue(result.metadata.processingNote!!.contains("embedded JPEG preview"))
            assertFalse(RawColorRecoveryEngine.isMonochrome(result.bitmap))
            assertNull(result.metadata.sensorDescription)
        } finally {
            file.delete()
        }
    }

    @Test
    fun unavailableColourKeepsMonochromeAndExplainsTheLimitation() = runBlocking {
        val file = rawWithEmbeddedPreview(Color.GRAY)
        try {
            val result = RawImageDecoder.decodeImage(context, Uri.fromFile(file))
            assertFalse(result.metadata.isColorRecovered)
            assertTrue(RawColorRecoveryEngine.isMonochrome(result.bitmap))
            assertTrue(result.metadata.processingNote!!.contains("monochrome image has been kept"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun dngSubIfdPreviewIsIdentifiedAsPreview() {
        val preview = jpeg(Color.rgb(45, 120, 200)).copyOf(60_000)
        val file = File.createTempFile("preview_", ".dng", context.cacheDir)
        try {
            val buffer = ByteBuffer.allocate(256 + preview.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x49, 0x49, 0x2a, 0))
            buffer.putInt(8)
            buffer.putShort(1)
            buffer.putShort(0x014a).putShort(4).putInt(1).putInt(32)
            buffer.position(32)
            buffer.putShort(5)
            for ((tag, value) in listOf(0x0100 to 800, 0x0101 to 600, 0x0103 to 7,
                0x0111 to 256, 0x0117 to preview.size)) {
                buffer.putShort(tag.toShort()).putShort(4).putInt(1).putInt(value)
            }
            buffer.position(256)
            buffer.put(preview)
            file.writeBytes(buffer.array())

            val extracted = DngColorExtractor.extractWithSource(file)
            assertNotNull(extracted)
            assertFalse(extracted!!.isSensorData)
            val monochrome = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GRAY)
            }
            val (result, recovered) = RawColorRecoveryEngine.recoverRealColors(
                context = context,
                file = file,
                decodedBitmap = monochrome,
                metadata = RawMetadata(format = RawFormat.DNG)
            )
            assertFalse(recovered)
            assertTrue(result === monochrome)
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
    fun decodableSensorDataCanStillRecoverColour() {
        val width = 128
        val height = 128
        val sensor = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sample = when {
                    y % 2 == 0 && x % 2 == 0 -> 60_000
                    y % 2 == 1 && x % 2 == 1 -> 4_000
                    else -> 10_000
                }
                sensor.putShort(sample.toShort())
            }
        }
        val file = File.createTempFile("sensor_", ".dng", context.cacheDir)
        try {
            val buffer = ByteBuffer.allocate(256 + sensor.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x49, 0x49, 0x2a, 0)).putInt(8)
            buffer.putShort(1).putShort(0x014a).putShort(4).putInt(1).putInt(32)
            buffer.position(32)
            buffer.putShort(7)
            for ((tag, value) in listOf(0x0100 to width, 0x0101 to height, 0x0102 to 16,
                0x0103 to 1, 0x0106 to 32803, 0x0111 to 256, 0x0117 to sensor.capacity())) {
                buffer.putShort(tag.toShort()).putShort(4).putInt(1).putInt(value)
            }
            buffer.position(256)
            buffer.put(sensor.array())
            file.writeBytes(buffer.array())

            val extracted = DngColorExtractor.extractWithSource(file)
            assertNotNull(extracted)
            assertTrue(extracted!!.isSensorData)
            val monochrome = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GRAY)
            }
            val (result, recovered) = RawColorRecoveryEngine.recoverRealColors(
                context = context,
                file = file,
                decodedBitmap = monochrome,
                metadata = RawMetadata(format = RawFormat.DNG)
            )
            assertTrue(recovered)
            assertFalse(RawColorRecoveryEngine.isMonochrome(result))
        } finally {
            file.delete()
        }
    }

    private fun rawWithEmbeddedPreview(color: Int): File {
        return File.createTempFile("embedded_", ".dng", context.cacheDir).apply {
            outputStream().use { output ->
                output.write(byteArrayOf(0x49, 0x49, 0x2a, 0, 8, 0, 0, 0))
                output.write(ByteArray(2048))
                output.write(jpeg(color))
            }
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
