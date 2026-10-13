package com.example.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "RawImageDecoder"
private const val MAX_DECODE_DIMENSION = 2560
private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
private const val MIN_FULL_RES_DIMENSION = 500 // Anything smaller is an EXIF thumbnail
private const val EMBEDDED_PREVIEW_NOTE = "Using the camera's embedded JPEG preview; RAW sensor data was not decoded."

data class RawDecodeResult(
    val bitmap: Bitmap,
    val metadata: RawMetadata,
    val sourceFile: File? = null,
    val sourceWidth: Int = bitmap.width,
    val sourceHeight: Int = bitmap.height
)

object RawImageDecoder {

    /**
     * Inspects URI and ContentResolver to retrieve file name and MIME type.
     */
    fun inspectUri(context: Context, uri: Uri): Pair<String?, String?> {
        var fileName: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query display name for $uri: ${e.message}")
        }
        if (fileName == null) {
            fileName = uri.lastPathSegment
        }
        val mimeType = context.contentResolver.getType(uri)
        return Pair(fileName, mimeType)
    }

    /** The returned staged source belongs to the caller when keepSource is true. */
    suspend fun decodeImage(context: Context, uri: Uri, keepSource: Boolean = false): RawDecodeResult {
        var completed: RawDecodeResult? = null
        try {
            return withContext(Dispatchers.IO) {
                val (fileName, mimeType) = inspectUri(context, uri)
                val format = RawFormat.detect(fileName, mimeType)
                val job = currentCoroutineContext()
                val tempFile = File.createTempFile("raw_decode_", ".tmp", context.cacheDir)
                var previewFile: File? = null
                var retained: File? = null
                var ownedBitmap: Bitmap? = null
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> copyBounded(input, output) { job.ensureActive() } }
                    } ?: throw IllegalStateException("Could not open the photo.")
                    val metadata = extractMetadataFromFile(tempFile, fileName, format)
                    var decodedBitmap: Bitmap? = null
                    var processingNote: String? = null
                    var orientationApplied = false
                    var sourceWidth = 0
                    var sourceHeight = 0
                    var decodeFile = tempFile

                    // shortcut: RAW uses previews or Android; add a calibrated decoder when unsupported sensor formats are needed.
                    if (format.isRaw) {
                        try {
                            previewFile = stageLargestEmbeddedJpeg(tempFile) { job.ensureActive() }
                            previewFile?.let { preview ->
                                val outerOrientation = runCatching {
                                    ExifInterface(tempFile).getAttribute(ExifInterface.TAG_ORIENTATION)?.toIntOrNull()?.takeIf { it in 1..8 }
                                }.getOrNull()
                                val previewExif = ExifInterface(preview)
                                val ownOrientation = previewExif.getAttribute(ExifInterface.TAG_ORIENTATION)
                                    ?.toIntOrNull()?.takeIf { it in 1..8 }
                                if (ownOrientation == null && outerOrientation != null) previewExif.apply {
                                    setAttribute(ExifInterface.TAG_ORIENTATION, outerOrientation.toString())
                                    saveAttributes()
                                }
                                val bitmap = decodeWithBitmapFactory(preview).also { ownedBitmap = it }
                                if (bitmap != null && isValidResolution(bitmap)) {
                                    decodedBitmap = bitmap
                                    decodeFile = preview
                                    processingNote = EMBEDDED_PREVIEW_NOTE
                                } else {
                                    bitmap?.recycle()
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "Embedded JPEG unavailable: ${error.message}")
                        }
                    }

                    if (decodedBitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val platform = decodeWithImageDecoder(tempFile)
                            if (!format.isRaw || isValidResolution(platform.bitmap)) {
                                decodedBitmap = platform.bitmap
                                ownedBitmap = platform.bitmap
                                sourceWidth = platform.width
                                sourceHeight = platform.height
                                orientationApplied = true
                            } else {
                                platform.bitmap.recycle()
                            }
                        } catch (error: Exception) {
                            Log.w(TAG, "ImageDecoder failed: ${error.message}")
                        }
                    }
                    if (decodedBitmap == null) decodedBitmap = decodeWithBitmapFactory(tempFile).also { ownedBitmap = it }
                    var bitmap = decodedBitmap ?: throw IllegalStateException("This image format could not be opened.")
                    job.ensureActive()

                    if (!orientationApplied) {
                        val dimensions = orientedDimensions(decodeFile)
                        sourceWidth = dimensions.first
                        sourceHeight = dimensions.second
                        val oriented = applyOrientation(bitmap, decodeFile)
                        if (oriented !== bitmap) bitmap.recycle()
                        bitmap = oriented
                        ownedBitmap = oriented
                    }
                    if (sourceWidth <= 0 || sourceHeight <= 0) {
                        sourceWidth = bitmap.width
                        sourceHeight = bitmap.height
                    }
                    if (format.isRaw && processingNote == null) {
                        processingNote = "Android opened this RAW file; it may use the camera's preview."
                    }
                    val orientationDegrees = if (processingNote == EMBEDDED_PREVIEW_NOTE) {
                        when (readOrientation(decodeFile)) { 5, 6 -> 90; 3 -> 180; 7, 8 -> 270; else -> 0 }
                    } else metadata.orientationDegrees
                    val rotated = orientationDegrees in listOf(90, 270)
                    val finalMetadata = metadata.copy(
                        originalWidth = max(metadata.originalWidth, if (rotated) sourceHeight else sourceWidth),
                        originalHeight = max(metadata.originalHeight, if (rotated) sourceWidth else sourceHeight),
                        processingNote = processingNote,
                        orientationDegrees = orientationDegrees
                    )
                    if (keepSource) retained = decodeFile
                    RawDecodeResult(bitmap, finalMetadata, retained, sourceWidth, sourceHeight).also { completed = it }
                } finally {
                    if (tempFile !== retained) tempFile.delete()
                    if (previewFile !== retained) previewFile?.delete()
                    if (completed == null && ownedBitmap?.isRecycled == false) ownedBitmap?.recycle()
                }
            }
        } catch (error: Throwable) {
            // withContext can cancel after decoding but before handing ownership to the caller.
            completed?.sourceFile?.delete()
            completed?.bitmap?.recycle()
            throw error
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_SOURCE_BYTES) { "This photo file is too large to import (maximum 512 MB)." }
            output.write(buffer, 0, count)
        }
        checkActive()
    }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orientedDimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (readOrientation(file) in 5..8) options.outHeight to options.outWidth
        else options.outWidth to options.outHeight
    }

    private fun isValidResolution(bitmap: Bitmap): Boolean {
        return bitmap.width >= MIN_FULL_RES_DIMENSION || bitmap.height >= MIN_FULL_RES_DIMENSION
    }

    private data class JpegStreamCandidate(
        val offset: Long,
        val width: Int,
        val height: Int
    ) {
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    /** Finds a real JPEG stream without interpreting any RAW sensor bytes. */
    fun extractLargestEmbeddedJpeg(file: File): Bitmap? {
        val jpeg = stageLargestEmbeddedJpeg(file) {} ?: return null
        return try { decodeWithBitmapFactory(jpeg) } finally { jpeg.delete() }
    }

    private fun stageLargestEmbeddedJpeg(file: File, checkActive: () -> Unit): File? {
        if (!file.exists() || file.length() < 1000) return null
        val candidates = mutableListOf<JpegStreamCandidate>()
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(256 * 1024)
            var position = 0L
            while (position < raf.length() - 4) {
                checkActive()
                raf.seek(position)
                val count = raf.read(buffer)
                if (count < 4) break
                for (i in 0 until count - 3) {
                    if (buffer[i] == 0xff.toByte() && buffer[i + 1] == 0xd8.toByte() && buffer[i + 2] == 0xff.toByte()) {
                        val offset = position + i
                        readJpegDimensions(raf, offset)?.let { (width, height) ->
                            candidates.add(JpegStreamCandidate(offset, width, height))
                            // A camera has only a few previews; cap marker-like malformed input.
                            if (candidates.size > 32) candidates.removeAt(candidates.indices.minBy { candidates[it].pixels })
                        }
                    }
                }
                position += count - 3
            }
            for (candidate in candidates.sortedByDescending { it.pixels }) {
                checkActive()
                val end = jpegEnd(raf, candidate.offset, checkActive) ?: continue
                val output = File.createTempFile("raw_preview_", ".jpg", file.parentFile)
                try {
                    raf.seek(candidate.offset)
                    output.outputStream().use { stream ->
                        var remaining = end - candidate.offset
                        while (remaining > 0) {
                            checkActive()
                            val count = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(count > 0) { "Embedded preview is truncated." }
                            stream.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(output.absolutePath, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) return output
                } catch (error: Throwable) {
                    output.delete()
                    throw error
                }
                output.delete()
            }
        }
        return null
    }

    /** Segment lengths prevent an EXIF thumbnail's EOI from truncating the master JPEG. */
    private fun jpegEnd(raf: RandomAccessFile, start: Long, checkActive: () -> Unit): Long? {
        raf.seek(start + 2)
        var entropy = false
        var checkedAt = start
        while (raf.filePointer < raf.length()) {
            if (raf.filePointer - checkedAt > 64 * 1024) {
                checkActive()
                checkedAt = raf.filePointer
            }
            var marker: Int
            if (entropy) {
                marker = entropyMarker(raf, checkActive) ?: return null
                entropy = false
            } else {
                if (raf.readUnsignedByte() != 0xff) return null
                do {
                    if (raf.filePointer >= raf.length()) return null
                    marker = raf.readUnsignedByte()
                } while (marker == 0xff)
            }
            if (marker == 0xd9) return raf.filePointer
            if (marker == 0xd8 || marker == 0 || marker in 0xd0..0xd7) return null
            if (raf.filePointer + 2 > raf.length()) return null
            val length = raf.readUnsignedShort()
            if (length < 2 || raf.filePointer + length - 2 > raf.length()) return null
            raf.seek(raf.filePointer + length - 2)
            entropy = marker == 0xda
        }
        return null
    }

    private fun entropyMarker(raf: RandomAccessFile, checkActive: () -> Unit): Int? {
        val bytes = ByteArray(64 * 1024)
        var afterFF = false
        while (raf.filePointer < raf.length()) {
            checkActive()
            val start = raf.filePointer
            val count = raf.read(bytes)
            if (count <= 0) return null
            for (i in 0 until count) {
                val value = bytes[i].toInt() and 255
                if (afterFF) {
                    if (value == 255) continue
                    afterFF = false
                    if (value == 0 || value in 0xd0..0xd7) continue
                    raf.seek(start + i + 1)
                    return value
                }
                afterFF = value == 255
            }
        }
        return null
    }

    /** Header segments may contain large ICC profiles; skip them rather than guessing a header size. */
    private fun readJpegDimensions(raf: RandomAccessFile, offset: Long): Pair<Int, Int>? = try {
        raf.seek(offset + 2)
        var dimensions: Pair<Int, Int>? = null
        while (raf.filePointer + 4 < raf.length()) {
            if (raf.readUnsignedByte() != 0xff) break
            var marker: Int
            do { marker = raf.readUnsignedByte() } while (marker == 0xff && raf.filePointer < raf.length())
            if (marker == 0xda || marker == 0xd9 || marker == 0xd8 || marker == 0) break
            val length = raf.readUnsignedShort()
            if (length < 2 || raf.filePointer + length - 2 > raf.length()) break
            if (marker in listOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                if (length < 8) break
                raf.readUnsignedByte()
                val height = raf.readUnsignedShort()
                val width = raf.readUnsignedShort()
                if (width > 0 && height > 0) dimensions = width to height
                break
            }
            raf.seek(raf.filePointer + length - 2)
        }
        dimensions
    } catch (error: Exception) { null }

    /**
     * Decodes using Android P+ ImageDecoder with memory-safe target sample sizing.
     */
    private data class PlatformDecode(val bitmap: Bitmap, val width: Int, val height: Int)

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(file: File): PlatformDecode {
        val source = ImageDecoder.createSource(file)
        var width = 0
        var height = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            width = info.size.width
            height = info.size.height
            val origWidth = info.size.width
            val origHeight = info.size.height
            val maxDim = max(origWidth, origHeight)

            if (maxDim > MAX_DECODE_DIMENSION) {
                val scale = MAX_DECODE_DIMENSION.toFloat() / maxDim.toFloat()
                val targetW = (origWidth * scale).roundToInt().coerceAtLeast(1)
                val targetH = (origHeight * scale).roundToInt().coerceAtLeast(1)
                decoder.setTargetSize(targetW, targetH)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
        return PlatformDecode(bitmap, width, height)
    }

    /**
     * Decodes using standard BitmapFactory with inSampleSize bounds calculation.
     */
    private fun decodeWithBitmapFactory(file: File): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        FileInputStream(file).use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        var sampleSize = 1
        val maxDim = max(boundsOptions.outWidth, boundsOptions.outHeight)
        while (maxDim / sampleSize > MAX_DECODE_DIMENSION) sampleSize *= 2

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }

        return FileInputStream(file).use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    /**
     * Extracts photographic and EXIF telemetry from the local file.
     */
    fun extractMetadataFromFile(
        file: File,
        fileName: String?,
        format: RawFormat
    ): RawMetadata {
        var make: String? = null
        var model: String? = null
        var lensModel: String? = null
        var focalLength: String? = null
        var aperture: String? = null
        var shutterSpeed: String? = null
        var iso: String? = null
        var exposureBias: String? = null
        var dateTime: String? = null
        var colorSpace: String? = null
        var width = 0
        var height = 0
        var orientationDegrees = 0

        try {
            val exif = ExifInterface(file)

            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() }

            val rawFocal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            if (rawFocal > 0.0) {
                focalLength = "${rawFocal.roundToInt()}mm"
            }

            val rawAperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (rawAperture > 0.0) {
                aperture = String.format(Locale.US, "f/%.1f", rawAperture)
            }

            val rawExpTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            if (!rawExpTime.isNullOrBlank()) {
                val expDouble = rawExpTime.toDoubleOrNull()
                shutterSpeed = when {
                    expDouble != null && expDouble < 1.0 && expDouble > 0.0 -> {
                        "1/${(1.0 / expDouble).roundToInt()}s"
                    }
                    expDouble != null -> "${expDouble}s"
                    else -> rawExpTime
                }
            }

            val rawIso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
            if (!rawIso.isNullOrBlank()) {
                iso = "ISO $rawIso"
            }

            val rawBias = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0)
            if (rawBias != 0.0) {
                exposureBias = String.format(Locale.US, "%+.1f EV", rawBias)
            }

            dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

            colorSpace = when (exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0)) {
                1 -> "sRGB"
                2 -> "Adobe RGB"
                else -> null
            }

            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

            // Fallback to PixelX/PixelY if ImageWidth was a thumbnail dimension
            val pixelX = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
            val pixelY = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
            if (pixelX > width) width = pixelX
            if (pixelY > height) height = pixelY

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            orientationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading EXIF metadata for file: ${e.message}")
        }

        return RawMetadata(
            format = format,
            fileName = fileName,
            make = make,
            model = model,
            lensModel = lensModel,
            focalLength = focalLength,
            aperture = aperture,
            shutterSpeed = shutterSpeed,
            iso = iso,
            exposureBias = exposureBias,
            dateTime = dateTime,
            colorSpace = colorSpace,
            originalWidth = width,
            originalHeight = height,
            orientationDegrees = orientationDegrees
        )
    }

    fun extractMetadata(
        context: Context,
        uri: Uri,
        fileName: String?,
        format: RawFormat
    ): RawMetadata {
        val temp = File.createTempFile("raw_meta_", ".tmp", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            extractMetadataFromFile(temp, fileName, format)
        } catch (e: Exception) {
            RawMetadata(format = format, fileName = fileName)
        } finally {
            temp.delete()
        }
    }

    private fun applyOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read orientation: ${error.message}")
            return bitmap
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(270f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                else -> return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
