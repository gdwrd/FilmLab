package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.example.ui.GalleryExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun cancellationDuringWriteNeverPublishesThePendingPhoto() = runBlocking {
        cancelAtBoundary(publish = false)
    }

    @Test fun cancellationDuringPublishRemovesTheCopyBeforeReturningToTheCaller() = runBlocking {
        cancelAtBoundary(publish = true)
    }

    private suspend fun cancelAtBoundary(publish: Boolean) = kotlinx.coroutines.coroutineScope {
        val provider = BlockingGalleryProvider(File(context.cacheDir, "cancel-gallery.jpg"), publish)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val delivered = AtomicBoolean()
        val saving = launch(Dispatchers.Default) {
            GalleryExporter.save(context, photo(), "cancel-gallery.jpg")
            delivered.set(true)
        }
        try {
            assertTrue("Export did not reach the controlled IO boundary", provider.entered.await(10, TimeUnit.SECONDS))
            saving.cancel()
        } finally {
            provider.release.countDown()
        }
        withTimeout(10_000) { saving.join() }
        assertFalse(delivered.get())
        assertTrue(provider.deleted.get())
        assertEquals(publish, provider.published.get())
        assertFalse(provider.file.exists())
    }

    @Test
    @Config(sdk = [27], shadows = [DelayedMediaScanner::class])
    fun aLegacyScanCompletingAfterCancellationRemovesItsGalleryEntry() = runBlocking {
        DelayedMediaScanner.prepare()
        val provider = BlockingGalleryProvider(File(context.cacheDir, "unused-legacy-provider.jpg"), false)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val delivered = AtomicBoolean()
        val saving = launch(Dispatchers.Default) {
            GalleryExporter.save(context, photo(), "cancel-legacy-gallery.jpg")
            delivered.set(true)
        }
        assertTrue("Export did not reach the scanner", DelayedMediaScanner.entered.await(10, TimeUnit.SECONDS))
        val file = File(DelayedMediaScanner.path!!)
        assertTrue(file.exists())
        saving.cancel()
        withTimeout(10_000) { saving.join() }
        assertFalse(file.exists())
        assertFalse(delivered.get())
        DelayedMediaScanner.listener!!.onScanCompleted(file.absolutePath, provider.uri)
        assertTrue(provider.deleted.get())
        assertFalse(file.exists())
    }

    private fun photo() = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private class BlockingGalleryProvider(val file: File, private val blockPublish: Boolean) : ContentProvider() {
        val uri = Uri.parse("content://media/external/images/media/730")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicBoolean()
        val deleted = AtomicBoolean()
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri = this.uri
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (!blockPublish) pause()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            published.set(true)
            if (blockPublish) pause()
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted.set(true)
            file.delete()
            return 1
        }
        private fun pause() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Export boundary was not released" }
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }

    @Implements(MediaScannerConnection::class)
    class DelayedMediaScanner {
        companion object {
            lateinit var entered: CountDownLatch
            var path: String? = null
            var listener: MediaScannerConnection.OnScanCompletedListener? = null
            fun prepare() { entered = CountDownLatch(1); path = null; listener = null }
            @JvmStatic @Implementation
            fun scanFile(context: Context, paths: Array<String>, mimeTypes: Array<String>?, callback: MediaScannerConnection.OnScanCompletedListener?) {
                path = paths.single()
                listener = callback
                entered.countDown()
            }
        }
    }
}
