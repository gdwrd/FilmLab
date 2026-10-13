package com.example

import android.app.Application
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.ui.FilmLabViewModel
import com.example.ui.GalleryExporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharingTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val images get() = File(application.cacheDir, "images")
    private var activity: ActivityController<MainActivity>? = null
    private var provider: SharedPhotoProvider? = null

    @Before fun prepare() {
        images.deleteRecursively()
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
    }

    @After fun cleanup() {
        activity?.pause()?.stop()?.destroy()
        images.deleteRecursively()
        provider?.file?.delete()
        File(application.noBackupFilesDir, "edit-draft").deleteRecursively()
    }

    @Test fun sharedCopiesAreDistinctReadableJpegsInsideTheGrantedCachePath() = runBlocking {
        val first = GalleryExporter.share(application, photo(Color.RED), "../private/photo.jpg")
        val bytes = application.contentResolver.openInputStream(first)!!.use { it.readBytes() }
        val second = GalleryExporter.share(application, photo(Color.BLUE), "../private/photo.jpg")
        assertNotEquals(first, second)
        assertEquals("content", first.scheme)
        assertEquals("${application.packageName}.fileprovider", first.authority)
        assertEquals("image/jpeg", application.contentResolver.getType(first))
        assertArrayEquals(bytes, application.contentResolver.openInputStream(first)!!.use { it.readBytes() })
        assertEquals(24, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
        assertEquals(2, images.listFiles()!!.size)
        assertTrue(images.listFiles()!!.all { it.canonicalFile.parentFile == images.canonicalFile })
        val info = application.packageManager.resolveContentProvider(first.authority!!, 0)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(application, first.authority!!, File(application.noBackupFilesDir, "private-photo.png"))
        }
        Unit
    }

    @Test fun failedCompressionLeavesNoShareAndOnlyExpiredSharesAreRemoved() = runBlocking {
        images.mkdirs()
        val expired = File(images, "share-expired.jpg").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L) }
        val current = File(images, "share-current.jpg").apply { writeText("current") }
        val unrelated = File(images, "other.jpg").apply { writeText("keep"); setLastModified(1) }
        val recycled = photo(Color.RED).apply { recycle() }
        assertTrue(runCatching { GalleryExporter.share(application, recycled, "photo.jpg") }.isFailure)
        assertFalse(expired.exists())
        assertTrue(current.exists())
        assertTrue(unrelated.exists())
        assertEquals(setOf(current.name, unrelated.name), images.listFiles()!!.map { it.name }.toSet())
        Unit
    }

    @Test fun incomingShareRequiresASingleImageContentUri() {
        val content = Uri.parse("content://camera/photo/1")
        assertEquals(content, sharedImageUri(send(content)))
        assertEquals(content, sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/jpeg").apply {
            clipData = ClipData.newRawUri("Photo", content)
        }))
        assertNull(sharedImageUri(send(Uri.parse("file:///private/photo.jpg"))))
        assertNull(sharedImageUri(send(Uri.parse("https://example.com/photo.jpg"))))
        assertNull(sharedImageUri(send(content).setType("text/plain")))
        assertNull(sharedImageUri(send(content).setAction(Intent.ACTION_SEND_MULTIPLE)))
        assertNull(sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "not a URI")))
        assertNull(sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/png").apply {
            clipData = ClipData.newRawUri("Photo", content).apply { addItem(ClipData.Item(content)) }
        }))
    }

    @Test @Config(sdk = [27])
    fun incomingContentUriAlsoWorksBeforeTypedParcelableApi() {
        val content = Uri.parse("content://camera/photo/1")
        assertEquals(content, sharedImageUri(send(content)))
        assertNull(sharedImageUri(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "not a URI")))
    }

    @Test fun manifestOffersFilmLabForImagesAndUsesSingleTop() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(application.packageName)
        @Suppress("DEPRECATION")
        val matches = application.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
        val info = matches.first { it.activityInfo.name == MainActivity::class.java.name }.activityInfo
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test fun coldShareImportsOnceAndNewIntentImportsTheNextPhoto() {
        val source = registerProvider()
        val controller = Robolectric.buildActivity(MainActivity::class.java, send(source.uri)).setup()
        activity = controller
        val model = ViewModelProvider(controller.get())[FilmLabViewModel::class.java]
        waitFor { model.uiState.value.baseBitmap != null && !model.uiState.value.isImporting }
        val firstOpens = source.opens.get()
        assertEquals(24, model.uiState.value.baseBitmap!!.width)
        controller.recreate()
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        assertEquals(firstOpens, source.opens.get())
        controller.newIntent(send(source.uri))
        waitFor { source.opens.get() > firstOpens && !model.uiState.value.isImporting }
        assertNotNull(model.uiState.value.baseBitmap)
    }

    @Test fun unreadableOrWrongMimeSharePreservesTheCurrentEdit() {
        val source = registerProvider()
        val controller = Robolectric.buildActivity(MainActivity::class.java, send(source.uri)).setup()
        activity = controller
        val model = ViewModelProvider(controller.get())[FilmLabViewModel::class.java]
        waitFor { model.uiState.value.baseBitmap != null && !model.uiState.value.isImporting }
        val previous = model.uiState.value.baseBitmap
        source.failRead = true
        val opens = source.opens.get()
        controller.newIntent(send(source.uri))
        waitFor { source.opens.get() > opens }
        ShadowLooper.idleMainLooper()
        assertSame(previous, model.uiState.value.baseBitmap)
        assertFalse(model.uiState.value.isImporting)
        source.failRead = false
        source.mime = "text/plain"
        controller.newIntent(send(source.uri))
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        assertSame(previous, model.uiState.value.baseBitmap)
        source.mime = "image/png"
        source.file.writeText("Not an image")
        controller.newIntent(send(source.uri))
        waitFor { model.uiState.value.errorMessage != null && !model.uiState.value.isImporting }
        assertSame(previous, model.uiState.value.baseBitmap)
    }

    private fun registerProvider(): SharedPhotoProvider {
        val file = File(application.cacheDir, "incoming-share.png")
        file.outputStream().use { photo(Color.rgb(160, 95, 40)).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return SharedPhotoProvider(file).also {
            it.attachInfo(application, ProviderInfo().apply { authority = "share-tests" })
            ShadowContentResolver.registerProviderInternal("share-tests", it)
            provider = it
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            if (System.nanoTime() >= deadline) fail("Shared photo did not finish importing")
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun send(uri: Uri) = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private fun photo(color: Int) = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private class SharedPhotoProvider(val file: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://share-tests/photo/1")
        val opens = AtomicInteger()
        var failRead = false
        var mime = "image/png"
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens.incrementAndGet()
            if (failRead) throw FileNotFoundException("Grant expired")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri) = mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
