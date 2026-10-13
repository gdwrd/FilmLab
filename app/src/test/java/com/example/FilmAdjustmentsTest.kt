package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilmAdjustmentsTest {
    @Test
    fun exposureUsesLinearLightAndWarmthMovesRedAndBlueInOppositeDirections() = runBlocking {
        val source = flat(Color.rgb(128, 128, 128))
        assertSame(source, FilmEngine.applyAdjustments(source))
        val brighter = FilmEngine.applyAdjustments(source, exposure = 1f).getPixel(0, 0)
        val darker = FilmEngine.applyAdjustments(source, exposure = -1f).getPixel(0, 0)
        assertEquals(176, Color.red(brighter))
        assertEquals(92, Color.red(darker))
        val warm = FilmEngine.applyAdjustments(source, warmth = 1f).getPixel(0, 0)
        val cool = FilmEngine.applyAdjustments(source, warmth = -1f).getPixel(0, 0)
        assertTrue(Color.red(warm) > Color.red(source.getPixel(0, 0)))
        assertTrue(Color.blue(warm) < Color.blue(source.getPixel(0, 0)))
        assertEquals(Color.red(warm), Color.blue(cool))
        assertEquals(128, Color.green(warm))
        assertEquals(Color.rgb(128, 128, 128), source.getPixel(0, 0))
        assertSame(source, FilmEngine.applyAdjustments(source, Float.NaN, Float.POSITIVE_INFINITY))
    }

    @Test
    fun newFilmsAddWarmColourNeutralSlideAndFineMonochromeWithoutChangingZeroStrength() = runBlocking {
        val grey = flat(Color.rgb(128, 128, 128))
        val gold = FilmEngine.applyFilmFilter(grey, FilmPreset.GOLD_200, grainIntensity = 0f).getPixel(0, 0)
        val slide = FilmEngine.applyFilmFilter(grey, FilmPreset.EKTACHROME_E100, grainIntensity = 0f).getPixel(0, 0)
        assertTrue(Color.red(gold) > Color.green(gold))
        assertTrue(Color.green(gold) > Color.blue(gold))
        assertEquals(Color.red(slide), Color.green(slide))
        assertEquals(Color.red(slide), Color.blue(slide))
        val colour = flat(Color.rgb(210, 100, 65))
        val mono = FilmEngine.applyFilmFilter(colour, FilmPreset.ILFORD_DELTA_100).getPixel(0, 0)
        assertEquals(Color.red(mono), Color.green(mono))
        assertEquals(Color.red(mono), Color.blue(mono))
        for (preset in FilmPreset.entries) {
            assertSame(colour, FilmEngine.applyFilmFilter(colour, preset, intensity = 0f))
        }
        val fine = FilmEngine.applyFilmFilter(grey, FilmPreset.ILFORD_DELTA_100)
        val coarse = FilmEngine.applyFilmFilter(grey, FilmPreset.ILFORD_HP5)
        fun range(bitmap: Bitmap): Int {
            val values = pixels(bitmap).map { Color.red(it) }
            return values.max() - values.min()
        }
        assertTrue(range(fine) in 1 until range(coarse))
    }

    @Test
    fun glowCanBeRemovedOrReducedWithoutChangingGrainOrTheSource() = runBlocking {
        val source = flat(Color.rgb(10, 10, 10), 40, 40)
        for (y in 18..22) for (x in 18..22) source.setPixel(x, y, Color.WHITE)
        suspend fun glow(value: Float) = FilmEngine.applyFilmFilter(source, FilmPreset.CINESTILL_800T, grainIntensity = 0f, glowIntensity = value)
        val clean = Color.red(glow(0f).getPixel(17, 20))
        val half = Color.red(glow(.5f).getPixel(17, 20))
        val full = Color.red(glow(1f).getPixel(17, 20))
        assertTrue(clean < half && half < full)
        assertTrue(abs((half - clean) * 2 - (full - clean)) <= 2)
        assertEquals(Color.rgb(10, 10, 10), source.getPixel(17, 20))
        assertEquals(0, FilmEngine.requiredHalo(FilmPreset.CINESTILL_800T, 0f))
    }

    @Test
    fun tiledRenderingExactlyMatchesWholeImageAtBothPreviewAndSourceScales() = runBlocking {
        val source = Bitmap.createBitmap(113, 89, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val highlight = x in 36..44 && y in 24..49 || x in 76..86 && y in 57..64
                setPixel(x, y, if (highlight) Color.WHITE else Color.rgb((x * 7 + y * 3) % 180, (x * 2 + y * 5) % 180, (x + y * 9) % 180))
            }
            setHasAlpha(false)
        }
        for (scale in listOf(1f, 2.35f)) for (preset in FilmPreset.entries) {
            val expected = pixels(FilmEngine.applyFilmFilter(source, preset, resolutionScale = scale))
            val actual = IntArray(source.width * source.height)
            val halo = FilmEngine.requiredHalo(preset, resolutionScale = scale)
            for (top in 0 until source.height step 29) for (left in 0 until source.width step 37) {
                val right = min(source.width, left + 37)
                val bottom = min(source.height, top + 29)
                val paddedLeft = max(0, left - halo)
                val paddedTop = max(0, top - halo)
                val paddedRight = min(source.width, right + halo)
                val paddedBottom = min(source.height, bottom + halo)
                val tile = Bitmap.createBitmap(source, paddedLeft, paddedTop, paddedRight - paddedLeft, paddedBottom - paddedTop)
                val filtered = FilmEngine.applyFilmFilter(tile, preset, originX = paddedLeft, originY = paddedTop, resolutionScale = scale)
                filtered.getPixels(actual, top * source.width + left, source.width, left - paddedLeft, top - paddedTop, right - left, bottom - top)
            }
            assertTrue("${preset.name} at $scale must not have tile seams", expected.contentEquals(actual))
        }
    }

    @Test
    fun grainUsesTheSamePhysicalCoordinatesAtLargerExportSizes() = runBlocking {
        val preview = flat(Color.rgb(128, 128, 128), 23, 19)
        val large = flat(Color.rgb(128, 128, 128), 69, 57)
        for (preset in FilmPreset.entries) {
            val smallResult = FilmEngine.applyFilmFilter(preview, preset, glowIntensity = 0f)
            val largeResult = FilmEngine.applyFilmFilter(large, preset, glowIntensity = 0f, resolutionScale = 3f)
            for (y in 0 until preview.height) for (x in 0 until preview.width) {
                assertEquals("${preset.name} grain scale", smallResult.getPixel(x, y), largeResult.getPixel(x * 3, y * 3))
                assertEquals("${preset.name} grain cell", largeResult.getPixel(x * 3, y * 3), largeResult.getPixel(x * 3 + 2, y * 3 + 2))
            }
        }
    }

    private fun flat(colour: Int, width: Int = 30, height: Int = 30) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(colour)
        setHasAlpha(false)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
