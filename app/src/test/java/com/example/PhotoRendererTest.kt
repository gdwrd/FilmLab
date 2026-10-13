package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.EditSettings
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.film.PhotoRenderer
import com.example.raw.RawImageDecoder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PixelRegionDecoderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun neutralPreviewAndCompareRetainTheInputWithoutAnotherBitmap() = runBlocking {
        val bitmap = gradient(200, 140)
        try {
            assertSame(bitmap, PhotoRenderer.cropOriginal(bitmap, CropSettings()))
            assertSame(bitmap, PhotoRenderer.renderPreview(bitmap, EditSettings()))
            assertEquals(200 to 140, PhotoRenderer.dimensions(200, 140, CropSettings(), 2048))
        } finally { bitmap.recycle() }
    }

    @Test
    fun cropRotateAndStraightenUseTheSamePreviewAndExportGeometry() = runBlocking {
        val source = gradient(1000, 760)
        val file = write(source, Bitmap.CompressFormat.PNG)
        val crop = CropSettings(.15f, .1f, .85f, .8f, quarterTurns = 1, straightenDegrees = 7f)
        var original: Bitmap? = null
        var exported: Bitmap? = null
        try {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            try { assertSamePixels(source, decoded) } finally { decoded.recycle() }
            original = PhotoRenderer.cropOriginal(source, crop)
            exported = PhotoRenderer.renderSource(file, EditSettings(crop = crop))
            assertEquals(original!!.width to original!!.height, PhotoRenderer.dimensions(1000, 760, crop))
            assertTransformedPixels(original!!, exported!!)
        } finally {
            original?.recycle(); exported?.recycle(); source.recycle(); file.delete()
        }
    }

    @Test
    fun aspectFramesRespectRotationAndDoNotEnlarge() {
        val crop = PhotoRenderer.withAspect(1200, 800, CropSettings(quarterTurns = 1), CropAspect.FOUR_FIVE)
        val size = PhotoRenderer.dimensions(1200, 800, crop)
        assertEquals(800 to 1000, size)
        assertEquals(400 to 500, PhotoRenderer.dimensions(1200, 800, crop, 500))
        assertEquals(800 to 1000, PhotoRenderer.dimensions(1200, 800, crop, 2000))
    }

    @Test
    fun straightenedOpaquePhotoDoesNotExposeEmptyCorners() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE); setHasAlpha(false) }
        val cropped = PhotoRenderer.cropOriginal(source, CropSettings(straightenDegrees = 10f))
        try {
            for ((x, y) in listOf(1 to 1, cropped.width - 2 to 1, 1 to cropped.height - 2, cropped.width - 2 to cropped.height - 2)) {
                assertTrue("Straightened corner must be inside source", Color.red(cropped.getPixel(x, y)) > 250)
            }
            assertFalse(cropped.hasAlpha())
        } finally { cropped.recycle(); source.recycle() }
    }

    @Test
    fun grainAndGlowExportAcrossTileBoundariesMatchThePreviewExactly() = runBlocking {
        val source = gradient(1100, 760)
        val file = write(source, Bitmap.CompressFormat.PNG)
        try {
            for (preset in listOf(FilmPreset.PORTRA_400, FilmPreset.CINESTILL_800T, FilmPreset.ILFORD_DELTA_100)) {
                val edit = EditSettings(preset = preset, strength = .72f, grainIntensity = 1.2f, exposure = .4f, warmth = -.2f, glowIntensity = .6f)
                val preview = PhotoRenderer.renderPreview(source, edit)
                val export = PhotoRenderer.renderSource(file, edit)
                try { assertSamePixels(preview, export) } finally { preview.recycle(); export.recycle() }
            }
        } finally { source.recycle(); file.delete() }
    }

    @Test
    fun exportPreservesAvailableSourceSizeWhileSmallerCopyStaysBounded() = runBlocking {
        val source = gradient(3000, 1600)
        val file = write(source, Bitmap.CompressFormat.PNG)
        source.recycle()
        var full: Bitmap? = null
        var small: Bitmap? = null
        try {
            full = PhotoRenderer.renderSource(file, EditSettings())
            assertEquals(3000 to 1600, full!!.width to full!!.height)
            small = PhotoRenderer.renderSource(file, EditSettings(), 2048)
            assertEquals(2048 to 1092, small!!.width to small!!.height)
        } finally { full?.recycle(); small?.recycle(); file.delete() }
    }

    @Test
    fun allExifOrientationsRemainConsistentAtExport() = runBlocking {
        val source = gradient(600, 400)
        val file = write(source, Bitmap.CompressFormat.JPEG)
        source.recycle()
        try {
            for (orientation in 1..8) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val preview = RawImageDecoder.decodeImage(context, Uri.fromFile(file)).bitmap
                val exported = PhotoRenderer.renderSource(file, EditSettings())
                try {
                    assertEquals(preview.width, exported.width)
                    assertEquals(preview.height, exported.height)
                    assertSamePixels(preview, exported)
                } finally { preview.recycle(); exported.recycle() }
            }
        } finally { file.delete() }
    }

    @Test
    fun transparentSourceIsFlattenedExactlyAsThePreview() = runBlocking {
        val source = Bitmap.createBitmap(700, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128, 255, 255, 255)) }
        val file = write(source, Bitmap.CompressFormat.PNG)
        val preview = FilmEngine.normalizeTo35mmResolution(source)
        val export = PhotoRenderer.renderSource(file, EditSettings())
        try { assertSamePixels(preview, export); assertFalse(export.hasAlpha()) }
        finally { source.recycle(); preview.recycle(); export.recycle(); file.delete() }
    }

    @Test
    fun oversizedLargestCopyFailsBeforeAllocatingItsPixels() = runBlocking {
        val source = gradient(600, 400)
        val file = write(source, Bitmap.CompressFormat.JPEG)
        source.recycle()
        try {
            val bytes = file.readBytes()
            val marker = (0 until bytes.size - 9).first { bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xc0.toByte() }
            bytes[marker + 5] = (65000 ushr 8).toByte()
            bytes[marker + 6] = 65000.toByte()
            bytes[marker + 7] = (65000 ushr 8).toByte()
            bytes[marker + 8] = 65000.toByte()
            file.writeBytes(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            assertEquals(65000, bounds.outWidth)
            val error = runCatching { PhotoRenderer.renderSource(file, EditSettings()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains("Choose Smaller copy"))
        } finally { file.delete() }
    }

    @Test
    fun missingSourceReportsAUsefulReopenAction() = runBlocking {
        val error = runCatching { PhotoRenderer.renderSource(File(context.cacheDir, "missing-original.jpg"), EditSettings()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("Reopen"))
    }

    private fun write(bitmap: Bitmap, format: Bitmap.CompressFormat): File = File.createTempFile("render_source_", if (format == Bitmap.CompressFormat.JPEG) ".jpg" else ".png", context.cacheDir).apply {
        outputStream().use { check(bitmap.compress(format, 100, it)) }
    }

    private fun gradient(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in 480..540 && y in 360..430) Color.WHITE
            else Color.rgb(x * 255 / width, y * 255 / height, (x + y) % 256)
        }
        setPixels(pixels, 0, width, 0, 0, width, height)
        setHasAlpha(false)
    }

    private fun assertTransformedPixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var differences = 0
        var maximumDelta = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val first = expected.getPixel(x, y)
            val second = actual.getPixel(x, y)
            if (first != second) differences++
            maximumDelta = maxOf(maximumDelta, kotlin.math.abs(Color.red(first) - Color.red(second)),
                kotlin.math.abs(Color.green(first) - Color.green(second)), kotlin.math.abs(Color.blue(first) - Color.blue(second)))
        }
        // Skia's 4-bit bilinear weights can cross one quantized boundary when a tile rebases its source.
        assertTrue("Transformed pixels: $differences differences", differences <= maxOf(1, expected.width * expected.height / 10_000))
        assertTrue("Transformed pixels: maximum channel difference $maximumDelta", maximumDelta <= 16)
    }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val first = IntArray(expected.width * expected.height)
        val second = IntArray(actual.width * actual.height)
        expected.getPixels(first, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(second, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(first, second)
    }
}

/** Robolectric's stock region shadow returns blank sizes; native BitmapFactory supplies real pixels here. */
@Implements(BitmapRegionDecoder::class)
class PixelRegionDecoderShadow {
    private var source: Bitmap? = null

    companion object {
        @Implementation
        @JvmStatic
        fun newInstance(path: String, shareable: Boolean): BitmapRegionDecoder {
            val decoder = ReflectionHelpers.callConstructor(BitmapRegionDecoder::class.java,
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, 0L))
            Shadow.extract<PixelRegionDecoderShadow>(decoder).source = checkNotNull(BitmapFactory.decodeFile(path))
            return decoder
        }
    }

    @Implementation
    fun decodeRegion(rect: Rect, options: BitmapFactory.Options): Bitmap {
        val image = checkNotNull(source)
        var cropped = Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
        if (cropped === image) cropped = checkNotNull(image.copy(Bitmap.Config.ARGB_8888, false))
        val sample = options.inSampleSize.coerceAtLeast(1)
        if (sample == 1) return cropped
        return Bitmap.createScaledBitmap(cropped, (rect.width() + sample - 1) / sample,
            (rect.height() + sample - 1) / sample, true).also { if (it !== cropped) cropped.recycle() }
    }

    @Implementation fun getWidth(): Int = checkNotNull(source).width
    @Implementation fun getHeight(): Int = checkNotNull(source).height
    @Implementation fun recycle() { source?.recycle(); source = null }
}
