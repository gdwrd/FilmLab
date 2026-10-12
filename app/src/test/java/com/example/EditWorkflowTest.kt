package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.raw.RawMetadata
import com.example.ui.EditDraftStore
import com.example.ui.FilmLabUiState
import com.example.ui.GalleryExporter
import com.example.ui.ZoomPanState
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditWorkflowTest {
    private lateinit var context: Context
    private lateinit var draftDirectory: File

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        draftDirectory = File(context.noBackupFilesDir, "edit-draft")
        draftDirectory.deleteRecursively()
    }

    @After fun cleanup() { draftDirectory.deleteRecursively() }

    private fun photo(color: Int) = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun draftRestoresExactPhotoAndLatestAdjustmentsWithoutRewritingPhoto() {
        val store = EditDraftStore(context)
        val original = photo(Color.rgb(190, 110, 70))
        val initial = FilmLabUiState(
            baseBitmap = original, selectedPreset = FilmPreset.PORTRA_400, intensity = .7f,
            rawMetadata = RawMetadata(fileName = "portrait.png", originalWidth = 24, originalHeight = 16, processingNote = "Using the embedded preview.")
        )
        store.save(initial)
        val firstPhoto = draftDirectory.listFiles()!!.single { it.extension == "png" }.name
        store.save(initial.copy(intensity = .4f, grainIntensity = 1.4f))
        assertEquals(firstPhoto, draftDirectory.listFiles()!!.single { it.extension == "png" }.name)
        val restored = EditDraftStore(context).load()!!
        assertTrue(original.sameAs(restored.baseBitmap))
        assertEquals(FilmPreset.PORTRA_400, restored.selectedPreset)
        assertEquals(.4f, restored.intensity, .0001f)
        assertEquals(1.4f, restored.grainIntensity, .0001f)
        assertEquals(initial.rawMetadata, restored.rawMetadata)
        assertFalse(restored.showWelcome)
    }

    @Test fun supersededPhotoWriteKeepsLastCompleteDraft() {
        val store = EditDraftStore(context)
        val previous = photo(Color.RED)
        store.save(FilmLabUiState(baseBitmap = previous))
        var checks = 0
        store.save(FilmLabUiState(baseBitmap = photo(Color.BLUE))) { ++checks == 1 }
        assertTrue(previous.sameAs(EditDraftStore(context).load()!!.baseBitmap))
        assertEquals(1, draftDirectory.listFiles()!!.count { it.extension == "png" })
    }

    @Test fun smallImagesKeepTheirRealResolution() {
        assertEquals(96 to 64, FilmEngine.calculate35mmDimensions(96, 64))
        assertEquals(64 to 96, FilmEngine.calculate35mmDimensions(64, 96))
        assertEquals(2048 to 1536, FilmEngine.calculate35mmDimensions(4000, 3000))
    }

    @Test fun zoomKeepsTheSamePhotoPositionWhenTheWindowResizes() {
        val zoom = ZoomPanState()
        zoom.updateContainerSize(1000f, 800f)
        zoom.onTransform(Offset(500f, 400f), Offset(100f, -60f), 2f)
        zoom.updateContainerSize(500f, 400f)
        assertEquals(2f, zoom.scale, .001f)
        assertEquals(50f, zoom.offsetX, .001f)
        assertEquals(-30f, zoom.offsetY, .001f)
    }

    @Test fun successfulExportPublishesAReadableJpeg() = runBlocking {
        val provider = GalleryProvider(File(context.cacheDir, "export-test.jpg"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = GalleryExporter.save(context, photo(Color.RED), "test.jpg")
        assertEquals(provider.uri, saved)
        assertTrue(provider.published)
        assertFalse(provider.deleted)
        val bitmap = BitmapFactory.decodeFile(provider.file.absolutePath)
        assertNotNull(bitmap)
        assertEquals(24, bitmap.width)
        provider.file.delete()
        Unit
    }

    @Test fun failedExportRemovesPendingGalleryEntry() = runBlocking {
        val provider = GalleryProvider(File(context.cacheDir, "export-failure.jpg"), failWrite = true)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val failure = runCatching { GalleryExporter.save(context, photo(Color.BLUE), "test.jpg") }
        assertTrue(failure.isFailure)
        assertTrue(provider.deleted)
        assertFalse(provider.published)
    }

    private class GalleryProvider(val file: File, private val failWrite: Boolean = false) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/images/media/123")
        var published = false
        var deleted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri = this.uri
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            published = values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; return 1 }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failWrite) throw FileNotFoundException("Storage is full")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
