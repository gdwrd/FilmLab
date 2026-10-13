package com.example

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.FilmPreset
import com.example.ui.FilmLabViewModel
import com.example.ui.filmOrder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NextEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var viewModel: FilmLabViewModel
    private val sources = mutableListOf<File>()

    @Before fun openSyntheticPhoto() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        viewModel = compose.runOnIdle { ViewModelProvider(compose.activity)[FilmLabViewModel::class.java] }
        importPhoto()
    }

    @After fun removeGeneratedSources() {
        sources.forEach { it.delete() }
    }

    @Test fun lightAndGlowAdjustmentsCropAndHistoryWorkOnDevice() {
        selectFilm(FilmPreset.CINESTILL_800T)
        compose.onNodeWithTag("adjust_button").performScrollTo().performClick()
        setSlider("exposure_slider", .5f)
        setSlider("warmth_slider", .3f)
        setSlider("glow_slider", .25f)
        screenshot("next-adjust")
        compose.onNodeWithText("Done").performClick()
        settle()
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(.5f, state.exposure, .001f)
            assertEquals(.3f, state.warmth, .001f)
            assertEquals(.25f, state.glowIntensity, .001f)
            assertFalse(state.baseBitmap!!.sameAs(state.filmFullBitmap))
        }

        compose.onNodeWithTag("crop_button").performScrollTo().performClick()
        compose.onNodeWithTag("crop_aspect_square").performScrollTo().performClick()
        setSlider("straighten_slider", 3f)
        compose.onNodeWithTag("rotate_crop_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CropSettings(), viewModel.uiState.value.crop) }
        compose.onNodeWithTag("cancel_crop_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CropSettings(), viewModel.uiState.value.crop) }

        compose.onNodeWithTag("crop_button").performScrollTo().performClick()
        compose.onNodeWithTag("crop_aspect_four_five").performScrollTo().performClick()
        setSlider("straighten_slider", 2f)
        compose.onNodeWithTag("rotate_crop_button").performScrollTo().performClick()
        screenshot("next-crop")
        compose.onNodeWithTag("apply_crop_button").performClick()
        settle()
        val applied = compose.runOnIdle { viewModel.uiState.value.crop }
        compose.runOnIdle {
            assertEquals(CropAspect.FOUR_FIVE, applied.aspect)
            assertEquals(1, applied.quarterTurns)
            assertEquals(2f, applied.straightenDegrees, .001f)
            val rendered = viewModel.uiState.value.filmFullBitmap!!
            assertEquals(.8f, rendered.width.toFloat() / rendered.height, .015f)
        }
        compose.onNodeWithTag("undo_button").performClick()
        settle()
        compose.runOnIdle {
            assertEquals(CropSettings(), viewModel.uiState.value.crop)
            assertEquals(.5f, viewModel.uiState.value.exposure, .001f)
        }
        compose.onNodeWithTag("redo_button").performClick()
        settle()
        compose.runOnIdle { assertEquals(applied, viewModel.uiState.value.crop) }
        screenshot("next-editor")
    }

    @Test fun favouritesAndValidatedRecipesApplyAndRenameWithoutChangingCrop() {
        val preset = FilmPreset.PORTRA_400
        val code = preset.shortCode.lowercase()
        compose.onNodeWithTag("all_looks_button").performScrollTo().performClick()
        if (preset !in viewModel.uiState.value.favouritePresets) {
            compose.onNodeWithTag("favourite_$code").performScrollTo().performClick()
        }
        compose.waitUntil(15_000) { preset in viewModel.uiState.value.favouritePresets }
        compose.onNodeWithTag("favourites_filter").performClick()
        compose.onNodeWithTag("all_preset_$code").assertIsDisplayed().performClick()
        settle()
        compose.onNodeWithTag("adjust_button").performScrollTo().performClick()
        setSlider("exposure_slider", .5f)
        setSlider("warmth_slider", .2f)
        compose.onNodeWithText("Done").performClick()
        settle()
        compose.onNodeWithTag("more_button").performScrollTo().performClick()
        compose.onNodeWithTag("save_look_button").performClick()
        compose.onNodeWithTag("look_name_field").performTextReplacement("")
        compose.onNodeWithTag("confirm_save_look_button").performScrollTo().performClick()
        compose.onNodeWithText("Give your look a name.").assertIsDisplayed()
        compose.onNodeWithTag("look_name_field").performTextReplacement("x".repeat(41))
        compose.onNodeWithTag("confirm_save_look_button").performScrollTo().performClick()
        compose.onNodeWithText("Use a name with 40 characters or fewer.").assertIsDisplayed()
        val name = "Device portrait ${System.nanoTime()}"
        compose.onNodeWithTag("look_name_field").performTextReplacement(name)
        compose.onNodeWithTag("confirm_save_look_button").performScrollTo().performClick()
        compose.waitUntil(15_000) { viewModel.uiState.value.savedLooks.any { it.name == name } }
        val recipe = viewModel.uiState.value.savedLooks.first { it.name == name }
        compose.onNodeWithTag("manage_look_${recipe.id}").performScrollTo().performClick()
        compose.onNodeWithTag("rename_look_button").performClick()
        val renamed = "Device warm ${System.nanoTime()}"
        compose.onNodeWithTag("look_name_field").performTextReplacement(renamed)
        compose.onNodeWithTag("confirm_save_look_button").performScrollTo().performClick()
        compose.waitUntil(15_000) { viewModel.uiState.value.savedLooks.any { it.id == recipe.id && it.name == renamed } }
        compose.onNodeWithText(renamed).assertIsDisplayed()
        screenshot("next-my-looks")
        compose.onNodeWithContentDescription("Back to editor").performClick()

        compose.onNodeWithTag("crop_button").performScrollTo().performClick()
        compose.onNodeWithTag("crop_aspect_square").performScrollTo().performClick()
        compose.onNodeWithTag("apply_crop_button").performClick()
        settle()
        val crop = viewModel.uiState.value.crop
        selectFilm(FilmPreset.GOLD_200)
        compose.onNodeWithTag("adjust_button").performScrollTo().performClick()
        setSlider("exposure_slider", -.5f)
        compose.onNodeWithText("Done").performClick()
        settle()
        compose.onNodeWithTag("all_looks_button").performScrollTo().performClick()
        compose.onNodeWithTag("my_looks_tab").performClick()
        compose.onNodeWithTag("apply_look_${recipe.id}").performScrollTo().performClick()
        settle()
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(preset, state.selectedPreset)
            assertEquals(.5f, state.exposure, .001f)
            assertEquals(.2f, state.warmth, .001f)
            assertEquals(crop, state.crop)
            assertEquals(recipe.settings, state.savedLooks.first { it.id == recipe.id }.settings)
        }
    }

    @Test fun recentEditsReopenAndRemovalCanBeUndoneThenShareOpensNativeChooser() {
        val firstId = viewModel.uiState.value.photoId!!
        selectFilm(FilmPreset.GOLD_200)
        val firstSource = sources.single()
        importPhoto()
        compose.waitUntil(15_000) { viewModel.uiState.value.recentEdits.any { it.id == firstId && it.preset == FilmPreset.GOLD_200 } }
        compose.runOnIdle { viewModel.openWelcome() }
        compose.onNodeWithTag("remove_recent_$firstId").performScrollTo().performClick()
        compose.onNodeWithText("Remove edit").performClick()
        compose.waitUntil(15_000) { viewModel.uiState.value.recentEdits.none { it.id == firstId } }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(15_000) { viewModel.uiState.value.recentEdits.any { it.id == firstId } }
        screenshot("next-recent-edits")
        compose.onNodeWithTag("recent_$firstId").performScrollTo().performClick()
        settle()
        compose.runOnIdle {
            assertEquals(firstId, viewModel.uiState.value.photoId)
            assertEquals(FilmPreset.GOLD_200, viewModel.uiState.value.selectedPreset)
            assertTrue(firstSource.isFile)
        }

        val dispatched = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) dispatched.set(Intent(intent))
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        var sharedFile: File? = null
        try {
            compose.onNodeWithTag("save_button").performClick()
            compose.onNodeWithTag("export_share_button").performScrollTo().performClick()
            compose.waitUntil(30_000) { dispatched.get() != null }
            val chooser = dispatched.get()
            @Suppress("DEPRECATION")
            val sharing = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, sharing.action)
            assertEquals("image/jpeg", sharing.type)
            assertTrue(sharing.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val uri = sharing.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(context.packageName + ".fileprovider", uri.authority)
            assertEquals(uri, sharing.clipData!!.getItemAt(0).uri)
            val jpeg = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            assertNotNull(jpeg)
            assertEquals(600, jpeg.width)
            assertEquals(400, jpeg.height)
            jpeg.recycle()
            sharedFile = File(context.cacheDir, "images/${uri.lastPathSegment}")
            compose.waitUntil(15_000) {
                val packageName = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                packageName.contains("intentresolver") || packageName.contains("systemui")
            }
            screenshot("next-native-share", waitForCompose = false)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            sharedFile?.delete()
        }
    }

    private fun selectFilm(preset: FilmPreset) {
        compose.onNodeWithTag("all_looks_button").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(filmOrder.indexOf(preset))
        compose.onNodeWithTag("all_preset_${preset.shortCode.lowercase()}").performScrollTo().performClick()
        settle()
    }

    private fun setSlider(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun importPhoto() {
        val source = File(context.cacheDir, "next-device-${System.nanoTime()}.png")
        sources += source
        val pixels = IntArray(600 * 400) { index ->
            val x = index % 600
            val y = index / 600
            if (x in 360..430 && y in 90..160) Color.WHITE
            else Color.rgb(40 + x * 180 / 600, 40 + y * 150 / 400, 75 + x * 70 / 600)
        }
        Bitmap.createBitmap(pixels, 600, 400, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val imported = compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(source)) }
        compose.waitUntil(30_000) { imported.isCompleted && !viewModel.uiState.value.isImporting }
        assertNotNull(viewModel.uiState.value.baseBitmap)
        settle()
    }

    private fun settle() {
        compose.waitUntil(30_000) { !viewModel.uiState.value.isProcessing && !viewModel.uiState.value.isImporting }
        compose.waitForIdle()
    }

    private fun screenshot(name: String, waitForCompose: Boolean = true) {
        if (waitForCompose) compose.waitForIdle()
        val folder = File(context.cacheDir, "design").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
