package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.film.FilmPreset
import com.example.raw.RawFormat
import com.example.raw.RawMetadata
import com.example.ui.EditDraftStore
import com.example.ui.FilmLabUiState
import com.example.ui.FilmLabViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoConsistencyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val models = ViewModelStore()

    @Before fun prepare() {
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
    }

    private fun editor() = compose.runOnIdle {
        FilmLabViewModel(application).also { models.put("editor", it) }
    }

    @Test fun transparentImportAndJpegExportStayConsistentAtFullStrength() {
        val viewModel = editor()
        val source = File(application.cacheDir, "transparent-photo.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 255, 255, 255))
        }
        try {
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val imported = compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(source)) }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                imported.isCompleted
            }
            val base = viewModel.uiState.value.baseBitmap!!
            assertFalse(base.hasAlpha())
            assertEquals(Color.rgb(136, 136, 137), base.getPixel(12, 8))
            assertEquals(128, Color.alpha(BitmapFactory.decodeFile(source.absolutePath).getPixel(12, 8)))
            compose.runOnIdle {
                viewModel.setGrainIntensity(0f)
                viewModel.selectPreset(FilmPreset.ILFORD_HP5)
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !viewModel.uiState.value.isProcessing
            }
            fun savedPixel(strength: Float): Int {
                compose.runOnIdle { viewModel.setIntensity(strength) }
                val output = runBlocking { viewModel.renderCurrentMasterBitmap()!! }
                assertEquals(255, Color.alpha(output.getPixel(12, 8)))
                val bytes = ByteArrayOutputStream().also {
                    assertTrue(output.compress(Bitmap.CompressFormat.JPEG, 97, it))
                }.toByteArray()
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).getPixel(12, 8)
            }
            val almostFull = savedPixel(.99f)
            val full = savedPixel(1f)
            assertTrue(kotlin.math.abs(Color.red(almostFull) - Color.red(full)) <= 2)
            assertTrue(kotlin.math.abs(Color.green(almostFull) - Color.green(full)) <= 2)
            assertTrue(kotlin.math.abs(Color.blue(almostFull) - Color.blue(full)) <= 2)
        } finally {
            source.delete()
            bitmap.recycle()
        }
    }

    @Test fun olderTransparentDraftIsNormalizedBeforeEditing() {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 255, 255, 255))
        }
        EditDraftStore(application).save(FilmLabUiState(baseBitmap = bitmap, intensity = .65f, grainIntensity = 1.2f))
        val viewModel = editor()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.uiState.value.baseBitmap != null
        }
        val restored = viewModel.uiState.value
        assertEquals(Color.rgb(136, 136, 137), restored.baseBitmap!!.getPixel(12, 8))
        assertFalse(restored.baseBitmap.hasAlpha())
        assertEquals(.65f, restored.intensity, .001f)
        assertEquals(1.2f, restored.grainIntensity, .001f)
        val output = runBlocking { viewModel.renderCurrentMasterBitmap()!! }
        assertTrue(restored.baseBitmap.sameAs(output))
    }

    @Test fun olderSensorDraftKeepsTheEditAndExplainsHowToRecoverAccurateColour() {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(63, 100, 244)) }
        EditDraftStore(application).save(FilmLabUiState(
            baseBitmap = bitmap, selectedPreset = FilmPreset.PORTRA_400, intensity = .65f,
            rawMetadata = RawMetadata(format = RawFormat.DNG, processingNote = "Developed from RAW sensor data. No colour data was recovered; the monochrome image has been kept.")
        ))
        val viewModel = editor()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            viewModel.uiState.value.baseBitmap != null
        }
        val restored = viewModel.uiState.value
        val originalPixels = IntArray(bitmap.width * bitmap.height)
        val restoredPixels = IntArray(originalPixels.size)
        bitmap.getPixels(originalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        restored.baseBitmap!!.getPixels(restoredPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertArrayEquals(originalPixels, restoredPixels)
        assertEquals(FilmPreset.PORTRA_400, restored.selectedPreset)
        assertEquals(.65f, restored.intensity, .001f)
        assertFalse(restored.rawMetadata!!.isColorRecovered)
        assertTrue(restored.rawMetadata.processingNote!!.contains("Reopen the original photo"))
        assertTrue(restored.errorMessage!!.contains("Reopen the original photo"))
    }
}
