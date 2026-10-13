package com.example

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.FilmPreset
import com.example.ui.FilmLabScreen
import com.example.ui.FilmLabViewModel
import com.example.ui.FilmLabUiState
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.ui.theme.FilmLabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
class NextEditorJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var viewModel: FilmLabViewModel

    @Before fun prepare() {
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
        File(application.noBackupFilesDir, "user-looks.json").delete()
        compose.runOnUiThread {
            viewModel = FilmLabViewModel(application)
            store.put("editor", viewModel)
        }
        compose.setContent { FilmLabTheme { FilmLabScreen(viewModel) } }
        importPhoto("editor-journey.png")
    }

    @After fun clear() {
        compose.runOnUiThread { store.clear() }
        File(application.cacheDir, "editor-journey.png").delete()
        File(application.cacheDir, "editor-second.png").delete()
    }

    @Test fun cropHasAWorkingCopyAndAppliedCropCanBeUndone() {
        compose.onNodeWithTag("crop_button").performScrollTo().performClick()
        compose.onNodeWithTag("crop_aspect_square").performClick()
        compose.onNodeWithTag("straighten_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.onNodeWithTag("rotate_crop_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CropSettings(), viewModel.uiState.value.crop) }
        compose.onNodeWithTag("cancel_crop_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CropSettings(), viewModel.uiState.value.crop) }

        compose.onNodeWithTag("crop_button").performScrollTo().performClick()
        compose.onNodeWithTag("crop_aspect_four_five").performClick()
        compose.onNodeWithTag("apply_crop_button").performClick()
        settle()
        compose.runOnIdle {
            assertEquals(CropAspect.FOUR_FIVE, viewModel.uiState.value.crop.aspect)
            val rendered = viewModel.uiState.value.filmFullBitmap!!
            assertEquals(.8f, rendered.width.toFloat() / rendered.height, .01f)
        }
        compose.onNodeWithTag("undo_button").performClick()
        settle()
        compose.runOnIdle { assertEquals(CropSettings(), viewModel.uiState.value.crop) }
        compose.onNodeWithTag("redo_button").performClick()
        settle()
        compose.runOnIdle { assertEquals(CropAspect.FOUR_FIVE, viewModel.uiState.value.crop.aspect) }
    }

    @Test fun holdingThePhotoKeepsComparisonVisibleUntilRelease() {
        compose.runOnIdle { viewModel.selectPreset(FilmPreset.PORTRA_400) }
        settle()
        compose.onNodeWithTag("viewport_image_box").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(viewModel.uiState.value.isComparing) }
        compose.onNodeWithTag("viewport_image_box").performTouchInput { up() }
        compose.runOnIdle { assertFalse(viewModel.uiState.value.isComparing) }
    }

    @Test fun originalWithLightCorrectionsCanBeHeldAndComparisonKeepsTheCropFrame() {
        val source = viewModel.uiState.value.baseBitmap
        compose.runOnIdle {
            viewModel.setExposure(.5f)
            viewModel.setWarmth(.3f)
        }
        settle()
        compose.onNodeWithTag("viewport_image_box").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(FilmPreset.ORIGINAL, viewModel.uiState.value.selectedPreset)
            assertTrue(viewModel.uiState.value.isComparing)
            assertSame(source, viewModel.uiState.value.comparisonBitmap)
        }
        compose.onNodeWithTag("viewport_image_box").performTouchInput { up() }
        compose.runOnIdle {
            assertFalse(viewModel.uiState.value.isComparing)
            viewModel.applyCrop(CropSettings(left = .1f, top = .2f, right = .9f, bottom = .9f,
                quarterTurns = 1, straightenDegrees = 2f, aspect = CropAspect.FREE))
        }
        settle()
        val frame = compose.onNodeWithTag("viewport_image_box").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport_image_box").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertTrue(state.isComparing)
            assertEquals(state.filmFullBitmap!!.width, state.comparisonBitmap!!.width)
            assertEquals(state.filmFullBitmap.height, state.comparisonBitmap.height)
        }
        assertEquals(frame, compose.onNodeWithTag("viewport_image_box").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("viewport_image_box").performTouchInput { up() }
        compose.runOnIdle { assertFalse(viewModel.uiState.value.isComparing) }
    }

    @Test fun favouritesAndSavedLooksWorkWithoutReplacingThePhotosCrop() {
        compose.onNodeWithTag("all_looks_button").performScrollTo().performClick()
        compose.onNodeWithTag("favourite_p-400").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); FilmPreset.PORTRA_400 in viewModel.uiState.value.favouritePresets }
        compose.onNodeWithTag("favourites_filter").performClick()
        compose.onNodeWithTag("all_preset_p-400").assertIsDisplayed().performClick()
        settle()
        compose.onNodeWithTag("adjust_button").performScrollTo().performClick()
        compose.onNodeWithTag("exposure_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        compose.onNodeWithText("Done").performClick()
        settle()
        compose.onNodeWithTag("more_button").performScrollTo().performClick()
        compose.onNodeWithTag("save_look_button").performClick()
        compose.onNodeWithTag("look_name_field").performTextReplacement("")
        compose.onNodeWithTag("confirm_save_look_button").performScrollTo().performClick()
        compose.onNodeWithText("Give your look a name.").assertIsDisplayed()
        compose.onNodeWithTag("look_name_field").performTextReplacement("Everyday portrait")
        compose.onNodeWithTag("confirm_save_look_button").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.uiState.value.savedLooks.size == 1 }
        val look = viewModel.uiState.value.savedLooks.single()
        compose.onNodeWithTag("apply_look_${look.id}").assertIsDisplayed()
        compose.runOnIdle {
            viewModel.applyCrop(CropSettings(left = .1f, right = .9f))
            viewModel.selectPreset(FilmPreset.GOLD_200)
            viewModel.setExposure(-.5f)
        }
        settle()
        compose.onNodeWithTag("apply_look_${look.id}").performClick()
        settle()
        compose.runOnIdle {
            assertEquals(FilmPreset.PORTRA_400, viewModel.uiState.value.selectedPreset)
            assertEquals(.5f, viewModel.uiState.value.exposure, .001f)
            assertEquals(.1f, viewModel.uiState.value.crop.left, .001f)
        }
    }

    @Test fun recentEditRemovalCanBeUndoneAndOriginalFilesRemain() {
        val firstId = viewModel.uiState.value.photoId!!
        compose.runOnIdle { viewModel.selectPreset(FilmPreset.PORTRA_400) }
        settle()
        importPhoto("editor-second.png")
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.uiState.value.recentEdits.size == 2 }
        compose.runOnIdle {
            assertTrue(viewModel.uiState.value.recentEdits.any { it.id == firstId && it.preset == FilmPreset.PORTRA_400 })
            viewModel.openWelcome()
        }
        compose.onNodeWithTag("remove_recent_$firstId").performScrollTo().performClick()
        compose.onNodeWithText("Remove edit").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.uiState.value.recentEdits.size == 1 }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); viewModel.uiState.value.recentEdits.size == 2 }
        compose.onNodeWithTag("recent_$firstId").performScrollTo().performClick()
        settle()
        compose.runOnIdle {
            assertEquals(firstId, viewModel.uiState.value.photoId)
            assertEquals(FilmPreset.PORTRA_400, viewModel.uiState.value.selectedPreset)
            assertTrue(File(application.cacheDir, "editor-journey.png").isFile)
        }
    }

    @Test fun shareUsesAndroidChooserWithAReadableImageGrant() {
        settle()
        compose.onNodeWithTag("save_button").performClick()
        compose.onNodeWithText("Largest available").assertIsDisplayed()
        compose.onNodeWithTag("export_share_button").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            shadowOf(compose.activity).peekNextStartedActivity() != null
        }
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val sharing = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, sharing.action)
        assertEquals("image/jpeg", sharing.type)
        assertTrue(sharing.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = sharing.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, sharing.clipData!!.getItemAt(0).uri)
        application.contentResolver.openInputStream(uri).use { assertNotNull(it) }
    }

    @Test fun exportButtonsWaitForThePreviewAndIncomingPhotosCloseTheSheet() {
        compose.onNodeWithTag("save_button").performClick()
        updateState { it.copy(isProcessing = true) }
        compose.onNodeWithTag("export_update_notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("export_save_button").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export_share_button").assertIsNotEnabled()
        updateState { it.copy(isProcessing = false, isImporting = true) }
        compose.onNodeWithTag("export_save_button").assertDoesNotExist()
        compose.onNodeWithTag("opening_photo").assertIsDisplayed()
        updateState { it.copy(isImporting = false) }
        compose.onNodeWithTag("save_button").assertIsDisplayed()
        compose.onNodeWithTag("export_save_button").assertDoesNotExist()
    }

    private fun updateState(update: (FilmLabUiState) -> FilmLabUiState) {
        compose.runOnIdle {
            val field = FilmLabViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(viewModel) as MutableStateFlow<FilmLabUiState>
            flow.value = update(flow.value)
        }
    }

    private fun importPhoto(name: String) {
        val source = File(application.cacheDir, name)
        Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(164, 118, 82))
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val imported = compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(source)) }
        compose.waitUntil(10_000) { compose.waitForIdle(); imported.isCompleted && !viewModel.uiState.value.isImporting }
        assertNotNull(viewModel.uiState.value.baseBitmap)
        settle()
    }

    private fun settle() {
        compose.waitUntil(10_000) {
            compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
            compose.waitForIdle()
            !viewModel.uiState.value.isProcessing && !viewModel.uiState.value.isImporting
        }
    }
}
