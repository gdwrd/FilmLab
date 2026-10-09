package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun readStringFromContext() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FilmLab", appName)
  }

  @Test
  fun testFilmResolutionNormalizationLandscape() {
    val (w, h) = FilmEngine.calculate35mmDimensions(4000, 3000)
    assertEquals(2048, w)
    assertEquals(1536, h)
  }

  @Test
  fun testFilmResolutionNormalizationPortrait() {
    val (w, h) = FilmEngine.calculate35mmDimensions(3000, 4000)
    assertEquals(1536, w)
    assertEquals(2048, h)
  }

  @Test
  fun testFilmResolutionNormalizationSquare() {
    val (w, h) = FilmEngine.calculate35mmDimensions(3000, 3000)
    assertEquals(2048, w)
    assertEquals(2048, h)
  }

  @Test
  fun testAllFilmPresetsProcessing() = runBlocking {
    val testBitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
    testBitmap.eraseColor(Color.rgb(180, 120, 90))

    for (preset in FilmPreset.values()) {
      val output = FilmEngine.applyFilmFilter(testBitmap, preset, 1.0f)
      assertNotNull("Preset ${preset.displayName} produced null output", output)
      assertEquals(60, output.width)
      assertEquals(60, output.height)
    }
  }

  @Test
  fun testKodachromeBlueSkyResponse() = runBlocking {
    // Typical digital camera blue sky: R=100, G=160, B=230 (slightly hazy/red polluted)
    val testBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    testBitmap.eraseColor(Color.rgb(100, 160, 230))

    val processed = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.KODACHROME_64, 1.0f)
    val outPixel = processed.getPixel(25, 25)
    val outR = Color.red(outPixel)
    val outB = Color.blue(outPixel)

    // Kodachrome cyan dye coupler suppresses red pollution in skies to yield deep cobalt/cerulean blue
    assertTrue("Red channel in blue sky should be suppressed (got $outR)", outR < 90)
    assertTrue("Blue channel should remain rich and vibrant (got $outB)", outB > 220)
    assertTrue("Blue dominance should be preserved or enhanced (B > R)", outB > outR + 100)
  }

  @Test
  fun testVelviaEmeraldGreenFoliageResponse() = runBlocking {
    // Typical foliage green: R=70, G=150, B=60
    val testBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
    testBitmap.eraseColor(Color.rgb(70, 150, 60))

    val processed = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.FUJI_VELVIA_50, 1.0f)
    val outPixel = processed.getPixel(20, 20)
    val outR = Color.red(outPixel)
    val outG = Color.green(outPixel)

    // Fuji Velvia emerald dye response suppresses yellow-red and boosts green
    assertTrue("Green should pop vividly (got $outG)", outG > 130)
    assertTrue("Red should be restrained relative to green (got $outR vs $outG)", outG > outR + 50)
  }

  @Test
  fun testPortraSkinToneWarmth() = runBlocking {
    // Portrait skin tone: R=210, G=160, B=130
    val testBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
    testBitmap.eraseColor(Color.rgb(210, 160, 130))

    val processed = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.PORTRA_400, 1.0f)
    val outPixel = processed.getPixel(20, 20)
    val outR = Color.red(outPixel)
    val outG = Color.green(outPixel)
    val outB = Color.blue(outPixel)

    // Portra 400 soft highlight compression and warm peachy balance
    assertTrue("Red remains prominent in skin tone", outR > outG)
    assertTrue("Warmth preserved: G > B", outG > outB)
    assertTrue("Highlights not harsh-clipped: R <= 255", outR <= 255)
  }

  @Test
  fun testCineStillHalationBloomNearSpecularHighlight() = runBlocking {
    // 40x40 black image with a bright specular highlight in center
    val testBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
    testBitmap.eraseColor(Color.rgb(10, 10, 10))
    // Specular highlight at center
    for (x in 18..22) {
      for (y in 18..22) {
        testBitmap.setPixel(x, y, Color.rgb(255, 255, 255))
      }
    }

    val processed = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.CINESTILL_800T, 1.0f)
    // Pixel right next to the highlight edge (x=17, y=20)
    val haloPixel = processed.getPixel(17, 20)
    val haloR = Color.red(haloPixel)
    val haloB = Color.blue(haloPixel)

    // CineStill red halation bloom should bleed red light into adjacent pixels
    assertTrue("Adjacent pixel should receive optical red halation bleed (got R=$haloR)", haloR > 12)
    assertTrue("Halation should have warm red bias (R > B, got R=$haloR, B=$haloB)", haloR >= haloB)
  }

  @Test
  fun testTriXHighContrastBwSeparation() = runBlocking {
    // Two grey levels: dark shadow (40) and bright midtone (170)
    val shadowBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(40, 40, 40))
    }
    val highlightBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(170, 170, 170))
    }

    val shadowOut = FilmEngine.applyFilmFilter(shadowBitmap, FilmPreset.KODAK_TRI_X, 1.0f)
    val highOut = FilmEngine.applyFilmFilter(highlightBitmap, FilmPreset.KODAK_TRI_X, 1.0f)

    val shadowLum = Color.red(shadowOut.getPixel(10, 10))
    val highLum = Color.red(highOut.getPixel(10, 10))

    // Kodak Tri-X 400 deep charcoal shadow and biting contrast
    assertTrue("Shadows should be deep charcoal (got $shadowLum)", shadowLum < 45)
    assertTrue("High contrast separation (contrast delta = ${highLum - shadowLum})", highLum - shadowLum > 90)
  }
}
