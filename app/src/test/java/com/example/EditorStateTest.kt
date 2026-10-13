package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.FilmPreset
import com.example.film.PhotoRenderer
import kotlinx.coroutines.runBlocking
import com.example.ui.ExportSize
import com.example.ui.EditDraftStore
import com.example.ui.FilmLabViewModel
import com.example.ui.SaveStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var application: Application
    private val models = ViewModelStore()
    private val fixtures = mutableListOf<File>()

    @Before fun prepare() {
        application = ApplicationProvider.getApplicationContext()
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
        File(application.noBackupFilesDir, "user-looks.json").delete()
        compose.setContent { }
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        fixtures.forEach(File::delete)
    }

    private fun editor(key: String = "editor") = compose.runOnIdle {
        FilmLabViewModel(application).also { models.put(key, it) }
    }

    private fun photo(width: Int = 96, height: Int = 64): File {
        val file = File.createTempFile("state-photo-", ".jpg", application.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(143, 114, 87)) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
        bitmap.recycle()
        fixtures += file
        return file
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000) {
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        condition()
    }

    private fun import(viewModel: FilmLabViewModel, source: File = photo()) {
        val job = compose.runOnIdle { viewModel.onImagePicked(Uri.fromFile(source)) }
        await { job.isCompleted && viewModel.uiState.value.baseBitmap != null && !viewModel.uiState.value.isProcessing }
    }

    @Test fun oneSliderGestureCreatesOneUndoStepAndANewChangeClearsRedo() {
        val model = editor()
        import(model)
        compose.runOnIdle {
            model.selectPreset(FilmPreset.PORTRA_400)
            model.beginAdjustment()
            model.setIntensity(.4f)
            model.setIntensity(.8f)
            model.setGrainIntensity(1.3f)
            model.finishAdjustment()
            model.undo()
        }
        assertEquals(FilmPreset.PORTRA_400, model.uiState.value.selectedPreset)
        assertEquals(1f, model.uiState.value.intensity, .001f)
        assertEquals(1f, model.uiState.value.grainIntensity, .001f)
        assertTrue(model.uiState.value.canRedo)
        compose.runOnIdle { model.undo() }
        assertEquals(FilmPreset.ORIGINAL, model.uiState.value.selectedPreset)
        compose.runOnIdle { model.redo(); model.setExposure(.6f); model.finishAdjustment() }
        assertEquals(FilmPreset.PORTRA_400, model.uiState.value.selectedPreset)
        assertFalse(model.uiState.value.canRedo)
        compose.runOnIdle { model.setExposure(Float.NaN); model.setWarmth(Float.POSITIVE_INFINITY) }
        assertEquals(.6f, model.uiState.value.exposure, .001f)
        assertEquals(0f, model.uiState.value.warmth, .001f)
    }

    @Test fun renamingALookKeepsItsRecipeAndApplyingItKeepsTheCurrentCrop() {
        val model = editor()
        import(model)
        compose.runOnIdle {
            model.selectPreset(FilmPreset.PORTRA_400)
            model.setExposure(.3f); model.setWarmth(.2f); model.setGrainIntensity(1.2f); model.finishAdjustment()
            assertNull(model.saveLook("Weekend"))
        }
        await { model.uiState.value.savedLooks.size == 1 }
        val look = model.uiState.value.savedLooks.single()
        val framing = CropSettings(left = .1f, right = .8f, aspect = CropAspect.FREE)
        compose.runOnIdle {
            model.selectPreset(FilmPreset.GOLD_200); model.setExposure(.8f); model.finishAdjustment()
            model.applyCrop(framing)
            assertNull(model.saveLook("Family", look.id))
        }
        await { model.uiState.value.savedLooks.single().name == "Family" }
        assertEquals(look.settings, model.uiState.value.savedLooks.single().settings)
        compose.runOnIdle { model.applyLook(look.id) }
        assertEquals(FilmPreset.PORTRA_400, model.uiState.value.selectedPreset)
        assertEquals(.3f, model.uiState.value.exposure, .001f)
        assertEquals(framing, model.uiState.value.crop)
        assertEquals("Give your look a name.", model.saveLook(" "))
    }

    @Test fun retainedSourceAndSettingsSurviveRestartWhileSmallerReportsItsActualPreviewSize() {
        val model = editor()
        import(model, photo(2600, 1400))
        assertEquals(2600, model.uiState.value.sourceWidth)
        assertEquals(1400, model.uiState.value.sourceHeight)
        assertEquals(2048, model.uiState.value.baseBitmap!!.width)
        assertTrue(model.uiState.value.sourceFile!!.isFile)
        val id = model.uiState.value.photoId
        compose.runOnIdle {
            model.setExposure(.5f); model.finishAdjustment()
            model.prepareExport()
        }
        assertEquals(2600, model.uiState.value.exportInfo!!.width)
        compose.runOnIdle { model.setExportSize(ExportSize.SMALLER) }
        assertEquals(model.uiState.value.baseBitmap!!.width, model.uiState.value.exportInfo!!.width)
        // A real photo-switch flushes the previous edit before a new source is published.
        import(model)
        compose.runOnUiThread { models.clear() }
        val restored = editor("restored")
        await { restored.uiState.value.recentEdits.size == 2 }
        compose.runOnIdle { restored.openRecentEdit(id!!) }
        await { restored.uiState.value.photoId == id && !restored.uiState.value.isImporting }
        assertEquals(.5f, restored.uiState.value.exposure, .001f)
        assertEquals(2600, restored.uiState.value.sourceWidth)
        assertTrue(restored.uiState.value.sourceFile!!.isFile)
    }

    @Test fun missingOriginalOffersPreviewAndReopeningPreservesTheEditIdentityAndFraming() {
        val model = editor()
        val source = photo(180, 120)
        import(model, source)
        val id = model.uiState.value.photoId
        val crop = PhotoRenderer.withAspect(180, 120, CropSettings(), CropAspect.SQUARE)
        compose.runOnIdle { model.applyCrop(crop); model.setExposure(.4f); model.finishAdjustment() }
        await { !model.uiState.value.isProcessing }
        val retained = model.uiState.value.sourceFile!!
        assertTrue(retained.delete())
        compose.runOnIdle { model.prepareExport() }
        assertFalse(model.uiState.value.exportInfo!!.sourceAvailable)
        assertTrue(model.uiState.value.exportInfo!!.limitation!!.contains("Original unavailable"))
        val job = compose.runOnIdle { model.reopenOriginal(Uri.fromFile(source)) }
        await { job.isCompleted && !model.uiState.value.isProcessing }
        assertEquals(id, model.uiState.value.photoId)
        assertEquals(crop, model.uiState.value.crop)
        assertEquals(.4f, model.uiState.value.exposure, .001f)
        assertTrue(model.uiState.value.sourceFile!!.isFile)
        assertTrue(model.uiState.value.exportInfo!!.sourceAvailable)
    }

    @Test fun removingAndRestoringAnotherPhotoKeepsTheCurrentPendingSettings() {
        val model = editor()
        import(model)
        val older = model.uiState.value.photoId!!
        import(model)
        val current = model.uiState.value.photoId!!
        compose.runOnIdle {
            model.setExposure(.75f)
            // Remove the other row before the current photo's debounced save can run.
            model.removeRecentEdit(older)
        }
        await { model.uiState.value.recentEdits.none { it.id == older } }
        await { EditDraftStore(application).load(current)?.exposure == .75f }
        assertEquals(current, model.uiState.value.photoId)
        compose.runOnIdle { model.undoRemoveRecentEdit() }
        await { model.uiState.value.recentEdits.any { it.id == older } }
        assertEquals(current, model.uiState.value.photoId)
        assertEquals(.75f, model.uiState.value.exposure, .001f)
        assertEquals(current, EditDraftStore(application).load()!!.photoId)
    }

    @Test fun cancelledExportCannotResurrectAnErrorWhenBlockedStorageLaterFails() {
        val model = editor()
        import(model)
        val provider = LateFailureProvider()
        provider.attachInfo(application, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            compose.runOnIdle { model.setExportSize(ExportSize.SMALLER); model.saveToGallery(application) }
            await { provider.opened.count == 0L }
            compose.runOnIdle { model.cancelExport() }
            provider.release.countDown()
            await { provider.deleted }
            compose.waitForIdle()
            assertEquals(SaveStatus.Idle, model.uiState.value.saveStatus)
            assertNull(model.uiState.value.errorMessage)
        } finally { provider.release.countDown() }
    }

    @Test fun originalLookThumbnailIncludesTheSameLightCorrectionsAsTheEditor() {
        val model = editor()
        import(model)
        compose.runOnIdle { model.setExposure(.6f); model.setWarmth(.3f); model.finishAdjustment() }
        val expected = runBlocking { model.renderCurrentMasterBitmap()!! }
        await { model.uiState.value.presetPreviews[FilmPreset.ORIGINAL]?.getPixel(12, 8) == expected.getPixel(12, 8) }
        assertTrue(expected.sameAs(model.uiState.value.presetPreviews[FilmPreset.ORIGINAL]))
    }

    @Test fun savedReceiptAndShareKeepTheFrozenVersionWhileTheEditorChanges() {
        val model = editor()
        import(model)
        val before = model.uiState.value.filmFullBitmap!!
        val file = File.createTempFile("saved-snapshot-", ".jpg", application.cacheDir).also { fixtures += it }
        val provider = LateFailureProvider(file)
        provider.attachInfo(application, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            compose.runOnIdle { model.setExportSize(ExportSize.SMALLER); model.saveToGallery(application) }
            await { provider.opened.count == 0L }
            compose.runOnIdle { model.setExposure(.8f); model.finishAdjustment() }
            provider.release.countDown()
            await { model.uiState.value.saveStatus is SaveStatus.Success && !model.uiState.value.isProcessing }
            val saved = model.uiState.value.saveStatus as SaveStatus.Success
            assertSame(before, saved.preview)
            assertEquals(before.width, saved.width)
            assertEquals(before.height, saved.height)
            assertNotEquals(before.getPixel(12, 8), model.uiState.value.filmFullBitmap!!.getPixel(12, 8))
            compose.runOnIdle { model.shareSavedCopy(saved.uri) }
            assertEquals(saved.uri, model.uiState.value.shareUri)
        } finally { provider.release.countDown() }
    }

    private class LateFailureProvider(private val destination: File? = null) : ContentProvider() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var deleted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri = Uri.parse("content://media/external/images/media/901")
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            destination?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE) }
            throw FileNotFoundException("Storage failed after cancellation")
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; return 1 }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
