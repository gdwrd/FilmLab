package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.film.FilmPreset
import com.example.ui.FilmLabViewModel
import com.example.ui.SaveStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilmLabJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun keepScreenAwake() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun editCompareAdjustAndSaveRealCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val viewModel = compose.runOnIdle { ViewModelProvider(compose.activity)[FilmLabViewModel::class.java] }
        val source = File(context.cacheDir, "journey-photo.png")
        BitmapFactory.decodeResource(context.resources, R.drawable.welcome_photo).also { bitmap ->
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        var savedUri: Uri? = null
        try {
            compose.runOnIdle { viewModel.openWelcome() }
            screenshot("welcome")
            compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(source)) }
            compose.waitUntil(15_000) { viewModel.uiState.value.baseBitmap != null && !viewModel.uiState.value.isImporting }
            compose.onNodeWithTag("preset_p-400").performClick()
            compose.waitUntil(15_000) { !viewModel.uiState.value.isProcessing }
            compose.runOnIdle { viewModel.setIntensity(.65f) }
            compose.onNodeWithTag("compare_button").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(viewModel.uiState.value.isComparing) }
            compose.onNodeWithTag("adjust_button").performClick()
            compose.runOnIdle {
                assertFalse(viewModel.uiState.value.isComparing)
                viewModel.setGrainIntensity(1.1f)
            }
            compose.waitUntil(15_000) { !viewModel.uiState.value.isProcessing }
            screenshot("adjust")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("save_button").assertIsDisplayed()
            screenshot("editor")
            compose.onNodeWithTag("more_button").performScrollTo().performClick()
            compose.onNodeWithTag("photo_info_button").performScrollTo().performClick()
            screenshot("info")
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNodeWithTag("save_button").performClick()
            compose.onNodeWithTag("export_save_button").performScrollTo().performClick()
            compose.waitUntil(15_000) { viewModel.uiState.value.saveStatus is SaveStatus.Success || viewModel.uiState.value.saveStatus is SaveStatus.Error }
            val result = viewModel.uiState.value.saveStatus
            assertTrue("Gallery write must succeed: " + result, result is SaveStatus.Success)
            savedUri = (result as SaveStatus.Success).uri
            val saved = context.contentResolver.openInputStream(savedUri!!).use { BitmapFactory.decodeStream(it) }
            assertNotNull(saved)
            assertEquals(viewModel.uiState.value.imageWidth, saved.width)
            assertEquals(viewModel.uiState.value.imageHeight, saved.height)
            assertTrue(source.exists())
            assertEquals(FilmPreset.PORTRA_400, viewModel.uiState.value.selectedPreset)
            screenshot("saved")
            compose.waitUntil(10_000) { File(context.noBackupFilesDir, "edit-draft/current.json").exists() }
        } finally {
            savedUri?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Android window transitions run outside the Compose test clock.
        android.os.SystemClock.sleep(700)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.cacheDir, "design").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
