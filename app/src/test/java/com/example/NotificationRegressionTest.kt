package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ui.FilmLabScreen
import com.example.ui.FilmLabUiState
import com.example.ui.FilmLabViewModel
import com.example.ui.SaveStatus
import com.example.ui.theme.FilmLabTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
class NotificationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var viewModel: FilmLabViewModel

    @Before fun prepare() {
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
        compose.runOnUiThread {
            viewModel = FilmLabViewModel(application)
            store.put("editor", viewModel)
        }
        compose.setContent { FilmLabTheme { FilmLabScreen(viewModel) } }
        val source = File(application.cacheDir, "notification-photo.png")
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(164, 118, 82))
        }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val imported = compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(source)) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            imported.isCompleted
        }
        assertNotNull(viewModel.uiState.value.baseBitmap)
    }

    @After fun clear() {
        compose.runOnUiThread { store.clear() }
        File(application.cacheDir, "notification-photo.png").delete()
    }

    @Test fun savedConfirmationExpiresWithoutOpeningViewer() {
        showSavedConfirmation()

        compose.mainClock.advanceTimeBy(12_000)
        compose.waitForIdle()

        compose.onNodeWithText("Saved to gallery").assertDoesNotExist()
        compose.runOnIdle { assertEquals(SaveStatus.Idle, viewModel.uiState.value.saveStatus) }
    }

    @Test fun importFailureImmediatelyReplacesSavedConfirmation() {
        showSavedConfirmation()

        val failed = compose.runOnIdle {
            viewModel.openWelcome()
            viewModel.onImagePicked(Uri.fromFile(File(application.cacheDir, "missing-notification-photo.png")))
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            failed.isCompleted
        }

        compose.onNodeWithText("Saved to gallery").assertDoesNotExist()
        compose.onNodeWithText("This photo could not be opened.", substring = true).assertIsDisplayed()
    }

    @Test fun replacingAnErrorDoesNotClearTheNewMessage() {
        updateState { it.copy(errorMessage = "First failure") }
        compose.onNodeWithText("First failure").assertIsDisplayed()

        updateState { it.copy(errorMessage = "Latest failure") }

        compose.onNodeWithText("First failure").assertDoesNotExist()
        compose.onNodeWithText("Latest failure").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Latest failure", viewModel.uiState.value.errorMessage) }
    }

    @Test fun newErrorKeepsPriorityOverWaitingSaveConfirmation() {
        updateState { it.copy(errorMessage = "First failure") }
        compose.onNodeWithText("First failure").assertIsDisplayed()
        updateState {
            it.copy(saveStatus = SaveStatus.Success("Saved to gallery", Uri.parse("content://media/external/images/media/1")))
        }
        compose.onNodeWithText("First failure").assertIsDisplayed()
        compose.onNodeWithText("Saved to gallery").assertDoesNotExist()

        updateState { it.copy(errorMessage = "Latest failure") }

        compose.onNodeWithText("Latest failure").assertIsDisplayed()
        compose.onNodeWithText("Saved to gallery").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Latest failure", viewModel.uiState.value.errorMessage) }

        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        compose.onNodeWithText("Latest failure").assertDoesNotExist()
        compose.onNodeWithText("Saved to gallery").assertIsDisplayed()
    }

    private fun showSavedConfirmation() {
        updateState {
            it.copy(saveStatus = SaveStatus.Success("Saved to gallery", Uri.parse("content://media/external/images/media/1")))
        }
        compose.onNodeWithText("Saved to gallery").assertIsDisplayed()
    }

    private fun updateState(update: (FilmLabUiState) -> FilmLabUiState) {
        // Inject notification completion without writing to a device gallery.
        compose.runOnIdle {
            val field = FilmLabViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(viewModel) as MutableStateFlow<FilmLabUiState>
            flow.value = update(flow.value)
        }
    }
}
