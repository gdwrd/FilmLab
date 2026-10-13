package com.example.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object GalleryExporter {
    suspend fun share(context: Context, bitmap: Bitmap, filename: String): Uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "images")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not prepare the shared photo")
        val oldest = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { it.name.startsWith("share-") && it.lastModified() < oldest }?.forEach { it.delete() }
        val title = filename.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "FilmLab" }
        val file = File.createTempFile("share-$title-", ".jpg", folder)
        try {
            FileOutputStream(file).use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) throw IOException("Could not write the shared photo")
            }
            currentCoroutineContext().ensureActive()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    suspend fun save(context: Context, bitmap: Bitmap, filename: String): Uri {
        var ownedUri: Uri? = null
        var ownedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FilmLab")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create the gallery copy")
                    ownedUri = uri
                    val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the gallery copy")
                    stream.use {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) throw IOException("Could not write the photo")
                    }
                    currentCoroutineContext().ensureActive()
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    if (resolver.update(uri, values, null, null) < 1) throw IOException("Could not publish the gallery copy")
                    uri
                } else {
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FilmLab")
                    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not open the Pictures folder")
                    val file = File(folder, filename)
                    ownedFile = file
                    FileOutputStream(file).use {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) throw IOException("Could not write the photo")
                    }
                    currentCoroutineContext().ensureActive()
                    suspendCancellableCoroutine { continuation ->
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg")) { _, uri ->
                            ownedUri = uri
                            if (continuation.isActive) {
                                if (uri != null) continuation.resume(uri)
                                else continuation.resumeWithException(IOException("Could not add the photo to the gallery"))
                            } else {
                                // A scan may finish after cancellation deleted its file.
                                file.delete()
                                uri?.let { runCatching { resolver.delete(it, null, null) } }
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            // withContext can discard a published result when its caller was cancelled.
            withContext(NonCancellable + Dispatchers.IO) {
                ownedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                ownedFile?.delete()
            }
            throw error
        }
    }
}
