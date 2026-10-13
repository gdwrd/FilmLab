package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.FilmPreset
import com.example.raw.RawMetadata
import com.example.ui.EditDraftStore
import com.example.ui.FilmLabUiState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecentEditsStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var source: File

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.noBackupFilesDir, "edit-draft")
        directory.deleteRecursively()
        source = File(context.cacheDir, "recent-edits-source.png")
        FileOutputStream(source).use { photo(Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @After fun cleanup() { directory.deleteRecursively(); source.delete() }

    private fun photo(color: Int) = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun state(id: String, color: Int = Color.RED) = FilmLabUiState(baseBitmap = photo(color), photoId = id,
        sourceFile = source, sourceWidth = 6000, sourceHeight = 4000, rawMetadata = RawMetadata(fileName = "$id.png"))

    @Test fun legacyDraftMigratesWithoutChangingPhotoOrSettings() {
        directory.mkdirs()
        val original = photo(Color.RED)
        FileOutputStream(File(directory, "photo-legacy.png")).use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "current.json").writeText(JSONObject().put("photo", "photo-legacy.png")
            .put("preset", "PORTRA_400").put("strength", .65).put("grain", 1.2).toString())

        val store = EditDraftStore(context)
        val restored = store.load()!!
        assertTrue(original.sameAs(restored.baseBitmap))
        assertEquals(FilmPreset.PORTRA_400, restored.selectedPreset)
        assertEquals(.65f, restored.intensity, .0001f)
        assertEquals(1.2f, restored.grainIntensity, .0001f)
        assertNotNull(restored.photoId)
        assertNull(restored.sourceFile)
        assertEquals(1, store.list().size)
        assertEquals(restored.photoId, EditDraftStore(context).load()!!.photoId)
        assertTrue(File(directory, "photo-legacy.png").exists())
    }

    @Test fun recentEditsRetainPrivateSourceAndAllAdjustmentsAcrossRestarts() {
        val store = EditDraftStore(context)
        val crop = CropSettings(.1f, .15f, .9f, .8f, 1, 3f, CropAspect.FOUR_FIVE)
        val first = state("first").copy(selectedPreset = FilmPreset.PORTRA_400, intensity = .6f, grainIntensity = 1.3f,
            exposure = -.8f, warmth = .2f, glowIntensity = .4f, crop = crop)
        store.save(first)
        store.save(state("second", Color.GREEN))
        val sourceBytes = source.readBytes()
        source.delete()

        val reopened = EditDraftStore(context)
        assertEquals(setOf("first", "second"), reopened.list().map { it.id }.toSet())
        assertEquals("second", reopened.load()!!.photoId)
        val restored = reopened.load("first")!!
        assertEquals(first.editSettings, restored.editSettings)
        assertEquals(6000, restored.sourceWidth)
        assertEquals(4000, restored.sourceHeight)
        assertTrue(restored.sourceFile!!.isFile)
        assertArrayEquals(sourceBytes, restored.sourceFile.readBytes())
        assertTrue(first.baseBitmap!!.sameAs(restored.baseBitmap))
        assertEquals(restored.sourceFile, reopened.sourceFileFor("first"))
    }

    @Test fun sliderChangesReusePreviewAndSourceRatherThanCopyingThem() {
        val store = EditDraftStore(context)
        val initial = state("first")
        store.save(initial)
        val files = directory.listFiles()!!.filter { it.name.startsWith("photo-") || it.name.startsWith("source-") }.map { it.name }.toSet()
        store.save(initial.copy(exposure = 1f))
        assertEquals(files, directory.listFiles()!!.filter { it.name.startsWith("photo-") || it.name.startsWith("source-") }.map { it.name }.toSet())
        assertEquals(1f, EditDraftStore(context).load()!!.exposure, 0f)
    }

    @Test fun canceledSourceWriteLeavesCommittedEditAndNoStagedAssets() {
        val store = EditDraftStore(context)
        val previous = state("first")
        store.save(previous)
        val files = directory.listFiles()!!.map { it.name }.toSet()
        var checks = 0
        store.save(state("second")) { ++checks < 3 }
        assertEquals(files, directory.listFiles()!!.map { it.name }.toSet())
        assertEquals("first", store.load()!!.photoId)
        assertEquals(1, store.list().size)
    }

    @Test fun deletingAnEditSupportsUndoAndNeverTouchesTheOriginal() {
        val store = EditDraftStore(context)
        store.save(state("first"))
        store.save(state("second", Color.GREEN))
        val retained = store.sourceFileFor("second")!!
        assertEquals("second.png", store.remove("second"))
        assertEquals(listOf("first"), store.list().map { it.id })
        assertNull(store.load("second"))
        assertTrue(retained.exists())
        assertTrue(EditDraftStore(context).undoRemove())
        assertEquals("first", store.load()!!.photoId)
        assertNotNull(store.load("second"))
        store.remove("second")
        store.purgeRemoved()
        assertFalse(retained.exists())
        assertFalse(store.undoRemove())
        assertTrue(source.exists())
        assertTrue(store.sourceFileFor("first")!!.exists())
    }

    @Test fun undoingTheOnlyDeletedEditMakesItCurrentAgain() {
        val store = EditDraftStore(context)
        store.save(state("only"))
        store.remove("only")
        assertNull(store.load())
        assertTrue(store.undoRemove())
        assertEquals("only", store.load()!!.photoId)
    }

    @Test fun anOldSnackbarDismissalCannotPurgeANewerDeletion() {
        val store = EditDraftStore(context)
        store.save(state("first"))
        store.save(state("second"))
        val firstSource = store.sourceFileFor("first")!!
        val secondSource = store.sourceFileFor("second")!!
        store.remove("first")
        store.remove("second")
        store.purgeRemoved("first")

        assertFalse(firstSource.exists())
        assertTrue(secondSource.exists())
        assertTrue(EditDraftStore(context).undoRemove())
        assertEquals("second", store.load()!!.photoId)
        assertTrue(source.exists())
    }

    @Test fun anOldUndoCannotRestoreANewerDeletion() {
        val store = EditDraftStore(context)
        store.save(state("first"))
        store.save(state("second"))
        val secondSource = store.sourceFileFor("second")!!
        store.remove("first")
        store.remove("second")

        assertFalse(store.undoRemove("first"))
        assertNull(store.load("second"))
        assertTrue(secondSource.exists())
        assertTrue(EditDraftStore(context).undoRemove("second"))
        assertEquals("second", store.load()!!.photoId)
    }

    @Test fun aLateSaveCannotResurrectDeletedDataOrRemoveAnotherPhoto() {
        val oldEditor = EditDraftStore(context)
        oldEditor.save(state("first"))
        val retained = oldEditor.load()!!
        val newerEditor = EditDraftStore(context)
        newerEditor.save(state("second", Color.GREEN))
        newerEditor.remove("first")
        oldEditor.save(retained.copy(warmth = .7f))
        newerEditor.purgeRemoved()
        oldEditor.save(retained.copy(warmth = .8f))
        assertEquals(listOf("second"), newerEditor.list().map { it.id })
        assertEquals("second", newerEditor.load()!!.photoId)
    }

    @Test fun storageLimitDoesNotEvictAnyExistingPhoto() {
        val store = EditDraftStore(context)
        repeat(20) { store.save(state("photo-$it")) }
        val before = store.list().map { it.id }.toSet()
        val failure = runCatching { store.save(state("overflow")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("20 recent edits"))
        assertEquals(before, EditDraftStore(context).list().map { it.id }.toSet())
        assertEquals("photo-19", store.load()!!.photoId)
    }

    @Test fun anOversizedOrMissingSourceLeavesTheLastEditAndAssetsUntouched() {
        val store = EditDraftStore(context)
        store.save(state("first"))
        val before = directory.listFiles()!!.map { it.name }.toSet()
        val oversized = File(context.cacheDir, "oversized-source.png")
        try {
            RandomAccessFile(oversized, "rw").use { it.setLength(256L * 1024 * 1024 + 1) }
            val failure = runCatching { store.save(state("second").copy(sourceFile = oversized)) }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("256 MB"))
            assertEquals(before, directory.listFiles()!!.map { it.name }.toSet())
            oversized.delete()
            assertTrue(runCatching { store.save(state("second").copy(sourceFile = oversized)) }.isFailure)
            assertEquals(before, directory.listFiles()!!.map { it.name }.toSet())
            assertEquals("first", store.load()!!.photoId)
        } finally { oversized.delete() }
    }

    @Test fun pathsInAManifestCannotReachOutsidePrivateStorage() {
        directory.mkdirs()
        File(directory, "current.json").writeText(JSONObject().put("photo", "../recent-edits-source.png").toString())
        assertTrue(runCatching { EditDraftStore(context).load() }.isFailure)
        assertTrue(source.exists())
    }
}
