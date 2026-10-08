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
  fun testKodachromeAndIlfordProcessing() = runBlocking {
    val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    testBitmap.eraseColor(Color.rgb(180, 120, 90))

    val kodachromeOutput = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.KODACHROME_64, 1.0f)
    assertNotNull(kodachromeOutput)
    assertEquals(100, kodachromeOutput.width)
    assertEquals(100, kodachromeOutput.height)

    val hp5Output = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.ILFORD_HP5, 1.0f)
    assertNotNull(hp5Output)
    assertEquals(100, hp5Output.width)
    assertEquals(100, hp5Output.height)
  }

  @Test
  fun testKodachromeGradientContinuityNoBanding() = runBlocking {
    // Generate a horizontal gradient ramp from 0 to 255
    val width = 256
    val height = 10
    val rampBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (x in 0 until width) {
      val c = Color.rgb(x, x, x)
      for (y in 0 until height) {
        rampBitmap.setPixel(x, y, c)
      }
    }

    // Process with Kodachrome
    val processed = FilmEngine.applyFilmFilter(rampBitmap, FilmPreset.KODACHROME_64, 1.0f)

    // Verify there are no massive step jumps (like the previous broken jump of >100 at x=128)
    var prevRed = Color.red(processed.getPixel(0, 5))
    for (x in 1 until width) {
      val curRed = Color.red(processed.getPixel(x, 5))
      val delta = abs(curRed - prevRed)
      // Consecutive steps in a 256-wide gradient must be smooth (< 15 including fine grain)
      assertTrue("Step jump too large at x=$x (delta=$delta, prev=$prevRed, cur=$curRed)", delta < 15)
      prevRed = curRed
    }
  }
}
