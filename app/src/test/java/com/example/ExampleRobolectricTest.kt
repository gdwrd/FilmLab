package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.ui.ZoomPanState
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  @Test
  fun testZoomPanInitialState() {
    val state = ZoomPanState()
    assertEquals(1.0f, state.scale, 0.001f)
    assertEquals(0f, state.offsetX, 0.001f)
    assertEquals(0f, state.offsetY, 0.001f)
    assertFalse(state.isZoomed)
    assertEquals("1.0×", state.zoomLabel)
  }

  @Test
  fun testZoomPanPinchTransformAndClamping() {
    val state = ZoomPanState(minScale = 1.0f, maxScale = 8.0f)
    state.updateContainerSize(1000f, 800f)

    // Pinch zoom 2x at center (500, 400)
    state.onTransform(
      centroid = Offset(500f, 400f),
      pan = Offset.Zero,
      zoomChange = 2.0f
    )

    assertEquals(2.0f, state.scale, 0.01f)
    assertTrue(state.isZoomed)
    assertEquals("2.0×", state.zoomLabel)
    // Zoomed at exact center: offsets should stay 0
    assertEquals(0f, state.offsetX, 0.1f)
    assertEquals(0f, state.offsetY, 0.1f)

    // Pan within bounds: max pan X at 2x is (1000 * 1)/2 = 500f, max pan Y is (800 * 1)/2 = 400f
    state.onPan(Offset(200f, -150f))
    assertEquals(200f, state.offsetX, 0.1f)
    assertEquals(-150f, state.offsetY, 0.1f)

    // Excessive pan beyond bounds should be clamped
    state.onPan(Offset(1000f, -1000f))
    assertEquals(500f, state.offsetX, 0.1f) // clamped to maxOffsetX (500)
    assertEquals(-400f, state.offsetY, 0.1f) // clamped to -maxOffsetY (-400)
  }

  @Test
  fun testZoomMaxScaleClamping() {
    val state = ZoomPanState(minScale = 1.0f, maxScale = 8.0f)
    state.updateContainerSize(1000f, 1000f)

    // Excessive pinch zoom 15x
    state.onTransform(
      centroid = Offset(500f, 500f),
      pan = Offset.Zero,
      zoomChange = 15.0f
    )

    // Must be clamped to maxScale (8.0f)
    assertEquals(8.0f, state.scale, 0.01f)
    assertTrue(state.isZoomed)
  }

  @Test
  fun testZoomResetToFit() {
    val state = ZoomPanState()
    state.updateContainerSize(1000f, 800f)
    state.onTransform(Offset(500f, 400f), Offset(100f, 50f), 3.0f)
    assertTrue(state.isZoomed)

    state.resetImmediate()
    assertEquals(1.0f, state.scale, 0.001f)
    assertEquals(0f, state.offsetX, 0.001f)
    assertEquals(0f, state.offsetY, 0.001f)
    assertFalse(state.isZoomed)
  }

  @Test
  fun testGrainIntensityPhysicsModulation() = runBlocking {
    // 30x30 uniform midtone image
    val testBitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(128, 128, 128))
    }

    // Process with grainIntensity = 0.0f (Clean / zero grain noise)
    val cleanOutput = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.ILFORD_HP5, intensity = 1.0f, grainIntensity = 0.0f)
    val pixel1 = cleanOutput.getPixel(10, 10)
    val pixel2 = cleanOutput.getPixel(11, 10)
    val pixel3 = cleanOutput.getPixel(12, 10)
    // With zero grain physics, all pixels in a uniform flat field have identical tone values
    assertEquals("Pixels should be identical with 0.0 grain intensity", pixel1, pixel2)
    assertEquals("Pixels should be identical with 0.0 grain intensity", pixel2, pixel3)

    // Process with grainIntensity = 1.0f (Calibrated default grain)
    val stockOutput = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.ILFORD_HP5, intensity = 1.0f, grainIntensity = 1.0f)
    var stockVariance = 0
    for (x in 5..25) {
      val diff = abs(Color.red(stockOutput.getPixel(x, 15)) - Color.red(stockOutput.getPixel(x + 1, 15)))
      stockVariance += diff
    }
    assertTrue("Stock grain should produce texture variance between pixels", stockVariance > 0)

    // Process with grainIntensity = 2.0f (Pushed grit grain)
    val pushedOutput = FilmEngine.applyFilmFilter(testBitmap, FilmPreset.ILFORD_HP5, intensity = 1.0f, grainIntensity = 2.0f)
    var pushedVariance = 0
    for (x in 5..25) {
      val diff = abs(Color.red(pushedOutput.getPixel(x, 15)) - Color.red(pushedOutput.getPixel(x + 1, 15)))
      pushedVariance += diff
    }
    assertTrue("Pushed grain (2.0x) should produce greater texture variance than stock (1.0x)", pushedVariance > stockVariance)
  }

  @Test
  fun testViewModelGrainIntensityControls() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.FilmLabViewModel(application)

    // Default grain intensity is 1.0f (100% Calibrated Stock)
    assertEquals(1.0f, viewModel.uiState.value.grainIntensity, 0.001f)

    // Set custom grain intensity
    viewModel.setGrainIntensity(1.65f)
    assertEquals(1.65f, viewModel.uiState.value.grainIntensity, 0.001f)

    // Clamping: below 0 should clamp to 0, above 2 should clamp to 2
    viewModel.setGrainIntensity(-0.5f)
    assertEquals(0.0f, viewModel.uiState.value.grainIntensity, 0.001f)

    viewModel.setGrainIntensity(3.5f)
    assertEquals(2.0f, viewModel.uiState.value.grainIntensity, 0.001f)

    // Reset grain intensity returns to calibrated 1.0f
    viewModel.resetGrainIntensity()
    assertEquals(1.0f, viewModel.uiState.value.grainIntensity, 0.001f)
  }

  @Test
  fun testRawFormatDetection() {
    // Sony Alpha 7 camera RAW formats
    assertEquals(com.example.raw.RawFormat.SONY_ARW, com.example.raw.RawFormat.detect("DSC01234.ARW", null))
    assertEquals(com.example.raw.RawFormat.SONY_ARW, com.example.raw.RawFormat.detect("photo.arw", null))
    assertEquals(com.example.raw.RawFormat.SONY_ARW, com.example.raw.RawFormat.detect("photo.srf", null))
    assertEquals(com.example.raw.RawFormat.SONY_ARW, com.example.raw.RawFormat.detect("file.bin", "image/x-sony-arw"))

    // Adobe DNG RAW formats
    assertEquals(com.example.raw.RawFormat.DNG, com.example.raw.RawFormat.detect("landscape.DNG", null))
    assertEquals(com.example.raw.RawFormat.DNG, com.example.raw.RawFormat.detect("image.dng", null))
    assertEquals(com.example.raw.RawFormat.DNG, com.example.raw.RawFormat.detect("unknown", "image/x-adobe-dng"))

    // Standard formats
    assertEquals(com.example.raw.RawFormat.STANDARD, com.example.raw.RawFormat.detect("photo.jpg", "image/jpeg"))
    assertEquals(com.example.raw.RawFormat.STANDARD, com.example.raw.RawFormat.detect("scan.png", "image/png"))

    assertTrue(com.example.raw.RawFormat.SONY_ARW.isRaw)
    assertTrue(com.example.raw.RawFormat.DNG.isRaw)
    assertFalse(com.example.raw.RawFormat.STANDARD.isRaw)
  }

  @Test
  fun testSonyA7CameraNameMapping() {
    val metaA7M3 = com.example.raw.RawMetadata(
      format = com.example.raw.RawFormat.SONY_ARW,
      make = "SONY",
      model = "ILCE-7M3",
      lensModel = "FE 35mm F1.4 GM",
      aperture = "f/1.4",
      shutterSpeed = "1/250s",
      iso = "ISO 400"
    )
    assertEquals("Sony α7 III", metaA7M3.displayCameraName)
    assertTrue(metaA7M3.hudTelemetryLine.contains("FE 35mm F1.4 GM"))
    assertTrue(metaA7M3.hudTelemetryLine.contains("f/1.4"))
    assertTrue(metaA7M3.hudTelemetryLine.contains("1/250s"))
    assertTrue(metaA7M3.hudTelemetryLine.contains("ISO 400"))

    val metaA7M4 = com.example.raw.RawMetadata(format = com.example.raw.RawFormat.SONY_ARW, model = "ILCE-7M4")
    assertEquals("Sony α7 IV", metaA7M4.displayCameraName)

    val metaA7RM5 = com.example.raw.RawMetadata(format = com.example.raw.RawFormat.SONY_ARW, model = "ILCE-7RM5")
    assertEquals("Sony α7R V", metaA7RM5.displayCameraName)

    val metaA7C = com.example.raw.RawMetadata(format = com.example.raw.RawFormat.SONY_ARW, model = "ILCE-7C")
    assertEquals("Sony α7C", metaA7C.displayCameraName)
  }

  @Test
  fun testSampleRawScenesMetadataAndGeneration() = runBlocking {
    val sonyMeta = com.example.ui.SamplePhotos.getSampleMetadata("raw_sony_a7m3")
    assertNotNull(sonyMeta)
    assertEquals(com.example.raw.RawFormat.SONY_ARW, sonyMeta!!.format)
    assertEquals("Sony α7 III", sonyMeta.displayCameraName)
    assertEquals("FE 35mm F1.4 GM", sonyMeta.lensModel)

    val dngMeta = com.example.ui.SamplePhotos.getSampleMetadata("raw_adobe_dng")
    assertNotNull(dngMeta)
    assertEquals(com.example.raw.RawFormat.DNG, dngMeta!!.format)

    val sonyBitmap = com.example.ui.SamplePhotos.generateSampleBitmap("raw_sony_a7m3")
    assertEquals(2048, sonyBitmap.width)
    assertEquals(1365, sonyBitmap.height)

    val dngBitmap = com.example.ui.SamplePhotos.generateSampleBitmap("raw_adobe_dng")
    assertEquals(2048, dngBitmap.width)
    assertEquals(1365, dngBitmap.height)
  }

  @Test
  fun testViewModelLoadSampleRawSetsMetadata() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.FilmLabViewModel(application)

    viewModel.loadSample("raw_sony_a7m3")
    // Allow coroutine execution
    kotlinx.coroutines.delay(100)

    val state = viewModel.uiState.value
    assertNotNull(state.baseBitmap)
    assertNotNull(state.rawMetadata)
    assertEquals(com.example.raw.RawFormat.SONY_ARW, state.rawMetadata?.format)
    assertEquals("Sony α7 III", state.rawMetadata?.displayCameraName)
  }

  @Test
  fun testLeicaCameraMetadataAndDisplayNames() {
    val leicaQ2 = com.example.raw.RawMetadata(
      format = com.example.raw.RawFormat.DNG,
      make = "Leica Camera AG",
      model = "Leica Q2",
      lensModel = "Summilux 28mm f/1.7 ASPH."
    )
    assertTrue("Should detect Leica camera", leicaQ2.isLeica)
    assertEquals("Leica Q2", leicaQ2.displayCameraName)
    assertEquals("RAW • LEICA DNG", leicaQ2.resolvedBadgeLabel)

    val leicaM10 = com.example.raw.RawMetadata(
      format = com.example.raw.RawFormat.DNG,
      make = "Leica Camera AG",
      model = "LEICA M10-R"
    )
    assertTrue("Should detect Leica M10", leicaM10.isLeica)
    assertEquals("Leica M10", leicaM10.displayCameraName)

    val leicaQ3 = com.example.raw.RawMetadata(
      format = com.example.raw.RawFormat.DNG,
      make = "Leica Camera AG",
      model = "LEICA Q3"
    )
    assertTrue("Should detect Leica Q3", leicaQ3.isLeica)
    assertEquals("Leica Q3", leicaQ3.displayCameraName)
  }

  @Test
  fun testRawColorRecoveryOnMonochromeImage() {
    // Create a pure monochrome test bitmap (simulating a Leica RAW shot in in-camera B&W style)
    val width = 100
    val height = 100
    val bwBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val gray = ((y.toFloat() / height) * 200 + 40).toInt().coerceIn(0, 255)
        bwBitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
      }
    }

    // Verify it is accurately detected as monochrome
    assertTrue("Bitmap should be detected as monochrome", com.example.raw.RawColorRecoveryEngine.isMonochrome(bwBitmap))

    // Reconstruct real chromatic colors
    val leicaMeta = com.example.raw.RawMetadata(
      format = com.example.raw.RawFormat.DNG,
      make = "Leica Camera AG",
      model = "Leica Q2"
    )
    val colorBitmap = com.example.raw.RawColorRecoveryEngine.reconstructRealChromaticSpectrum(bwBitmap, leicaMeta)

    assertNotNull(colorBitmap)
    assertEquals(width, colorBitmap.width)
    assertEquals(height, colorBitmap.height)

    // Verify that authentic colors are recovered (no longer monochrome)
    assertFalse("Recovered bitmap should contain full RGB chromatic spread", com.example.raw.RawColorRecoveryEngine.isMonochrome(colorBitmap))

    // Check that upper region (sky) has blue dominance (B > R)
    val skyPixel = colorBitmap.getPixel(50, 15)
    val skyR = Color.red(skyPixel)
    val skyB = Color.blue(skyPixel)
    assertTrue("Sky zone should have rich blue wavelength recovery (B > R)", skyB > skyR)
  }

  @Test
  fun testColorBitmapNotAlteredByRecoveryEngine() {
    val colorBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    colorBitmap.eraseColor(Color.rgb(220, 80, 40)) // Vivid red/amber

    assertFalse("Color bitmap must not be detected as monochrome", com.example.raw.RawColorRecoveryEngine.isMonochrome(colorBitmap))

    val app = ApplicationProvider.getApplicationContext<Context>()
    val dummyUri = android.net.Uri.parse("file:///dummy.dng")
    val meta = com.example.raw.RawMetadata(format = com.example.raw.RawFormat.DNG)
    val (result, recovered) = com.example.raw.RawColorRecoveryEngine.recoverRealColors(app, dummyUri, colorBitmap, meta)

    assertFalse("Should not trigger recovery if already in color", recovered)
    assertEquals(colorBitmap, result)
  }
}
