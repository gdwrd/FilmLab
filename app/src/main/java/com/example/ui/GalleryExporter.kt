package com.example.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object GalleryExporter {
    suspend fun save(context: Context, bitmap: Bitmap, filename: String): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FilmLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the gallery copy")
            try {
                val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the gallery copy")
                stream.use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) throw IOException("Could not write the photo")
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) < 1) throw IOException("Could not publish the gallery copy")
                uri
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FilmLab")
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not open the Pictures folder")
            val file = File(folder, filename)
            try {
                FileOutputStream(file).use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) throw IOException("Could not write the photo")
                }
                suspendCancellableCoroutine { continuation ->
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg")) { _, uri ->
                        if (continuation.isActive) {
                            if (uri != null) continuation.resume(uri)
                            else continuation.resumeWithException(IOException("Could not add the photo to the gallery"))
                        }
                    }
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }
}
