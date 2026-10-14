package com.example

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.film.FilmPreset
import com.example.ui.FilmLabScreen
import com.example.ui.FilmLabViewModel
import com.example.ui.theme.FilmLabTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
class FilmLabScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: FilmLabViewModel

    @Before
    fun prepare() {
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
        compose.runOnUiThread {
            viewModel = FilmLabViewModel(application)
            viewModelStore.put("editor", viewModel)
        }
    }

    @After
    fun clearViewModel() {
        compose.runOnUiThread { viewModelStore.clear() }
    }

    @Test
    fun welcomeOpensTheChosenNativePickerWithoutAnExtraDialog() {
        showScreen()

        compose.onAllNodesWithText("Choose photo").assertCountEquals(1)
        compose.onNodeWithTag("choose_photo_button").performScrollTo().assertIsEnabled().performClick()
        val galleryIntent = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(MediaStore.ACTION_PICK_IMAGES, galleryIntent.action)
        assertEquals("image/*", galleryIntent.type)

        compose.onNodeWithTag("browse_files_button").performScrollTo().assertTextEquals("Browse files").performClick()
        val filesIntent = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, filesIntent.action)
        assertEquals("*/*", filesIntent.type)

        compose.onNodeWithTag("save_button").assertDoesNotExist()
        compose.onNodeWithTag("intensity_slider").assertDoesNotExist()
        compose.onNodeWithTag("compare_button").assertDoesNotExist()
    }

    @Test
    fun filmStrengthCompareAndResetFormOneRecoverableEdit() {
        showScreen()
        importPhoto()

        compose.onNodeWithTag("preset_raw").assertIsSelected()
        compose.onNodeWithTag("intensity_slider").assertIsNotEnabled()
        compose.onNodeWithTag("preset_p-400").performClick().assertIsSelected()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !viewModel.uiState.value.isProcessing
        }

        compose.onNodeWithTag("intensity_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.35f) }
        compose.runOnIdle { assertEquals(.35f, viewModel.uiState.value.intensity, .001f) }

        compose.onNodeWithTag("compare_button").performScrollTo().performClick()
        compose.onNodeWithTag("compare_button").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Showing original")
        )
        compose.onNodeWithTag("viewport_image_box").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Original")
        )

        compose.onNodeWithTag("adjust_button").performClick()
        compose.onNodeWithTag("grain_slider").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        compose.runOnIdle {
            assertFalse(viewModel.uiState.value.isComparing)
            assertEquals(1.6f, viewModel.uiState.value.grainIntensity, .001f)
        }

        compose.onNodeWithTag("reset_adjustments_button").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(FilmPreset.PORTRA_400, viewModel.uiState.value.selectedPreset)
            assertEquals(1f, viewModel.uiState.value.intensity, .001f)
            assertEquals(1f, viewModel.uiState.value.grainIntensity, .001f)
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("save_button").assertIsDisplayed().assertTextEquals("Save")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun largeTextOnANarrowScreenKeepsSaveAndOutputInformationReachable() {
        showScreen(fontScale = 1.8f)
        importPhoto(width = 96, height = 64)

        compose.onNodeWithTag("save_button").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("more_button").performScrollTo().performClick()
        compose.onNodeWithTag("photo_info_button").performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithText("96 × 64 pixels")[0]
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("JPEG copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("save_button").assertIsDisplayed()
    }

    @Test
    fun portraitAndLandscapeExportPreviewsDoNotOverlapTheHeadingOrSizeControls() {
        showScreen()
        for ((width, height) in listOf(96 to 192, 192 to 96)) {
            importPhoto(width, height)
            compose.waitUntil(10_000) { compose.waitForIdle(); !viewModel.uiState.value.isProcessing }
            compose.onNodeWithTag("save_button").assertIsEnabled().performClick()

            val heading = compose.onNodeWithText("Save & share").assertIsDisplayed().getUnclippedBoundsInRoot()
            val preview = compose.onNodeWithContentDescription("Edited copy").assertIsDisplayed().getUnclippedBoundsInRoot()
            val size = compose.onNodeWithTag("export_largest").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Preview overlaps heading for $width × $height", heading.bottom <= preview.top)
            assertTrue("Preview overlaps size controls for $width × $height", preview.bottom <= size.top)

            compose.onNodeWithText("Done").performClick()
        }
    }

    @Test
    fun failedOrCancelledReplacementKeepsTheRetainedPhotosSelectedFilm() {
        showScreen()
        importPhoto()
        val original = viewModel.uiState.value.baseBitmap!!
        val missingPhoto = Uri.fromFile(File(application.cacheDir, "missing-photo.png"))

        val failedImport = compose.runOnIdle {
            viewModel.selectPreset(FilmPreset.ILFORD_HP5)
            viewModel.onImagePicked(missingPhoto)
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            failedImport.isCompleted && !viewModel.uiState.value.isProcessing
        }
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(FilmPreset.ILFORD_HP5, state.selectedPreset)
            assertTrue(state.baseBitmap === original)
            assertNotSame(original, state.filmFullBitmap)
            val pixel = state.filmFullBitmap!!.getPixel(24, 24)
            assertEquals(Color.red(pixel), Color.green(pixel))
            assertEquals(Color.green(pixel), Color.blue(pixel))
        }
        compose.onNodeWithText("This photo could not be opened.", substring = true).assertIsDisplayed()

        compose.runOnIdle {
            viewModel.selectPreset(FilmPreset.PORTRA_400)
            viewModel.onImagePicked(missingPhoto)
            viewModel.openWelcome()
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !viewModel.uiState.value.isImporting && !viewModel.uiState.value.isProcessing
        }
        compose.onNodeWithTag("continue_editing_button").performScrollTo().performClick()
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(FilmPreset.PORTRA_400, state.selectedPreset)
            assertTrue(state.baseBitmap === original)
            assertFalse(state.showWelcome)
            val pixel = state.filmFullBitmap!!.getPixel(24, 24)
            assertTrue(Color.red(pixel) > Color.green(pixel))
            assertTrue(Color.green(pixel) > Color.blue(pixel))
        }
        compose.onNodeWithTag("save_button").assertIsDisplayed().assertIsEnabled()
    }

    private fun showScreen(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FilmLabTheme { FilmLabScreen(viewModel) }
            }
        }
    }

    private fun importPhoto(width: Int = 96, height: Int = 64) {
        val file = File(application.cacheDir, "ui-test-photo.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(164, 118, 82))
        }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val import = compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(file)) }
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                import.isCompleted
            }
        } catch (error: Exception) {
            throw AssertionError("Photo import did not complete: ${viewModel.uiState.value}", error)
        }
        assertNotNull("Photo import failed: ${viewModel.uiState.value}", viewModel.uiState.value.baseBitmap)
        compose.waitForIdle()
    }
}
