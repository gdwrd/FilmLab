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
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "RawImageDecoder"
private const val MAX_DECODE_DIMENSION = 2560 // 35mm master lab scan resolution

data class RawDecodeResult(
    val bitmap: Bitmap,
    val metadata: RawMetadata
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

    /**
     * Decodes an image URI with full support for DNG and Sony ARW RAW formats.
     */
    suspend fun decodeImage(context: Context, uri: Uri): RawDecodeResult = withContext(Dispatchers.IO) {
        val (fileName, mimeType) = inspectUri(context, uri)
        val format = RawFormat.detect(fileName, mimeType)

        // Read EXIF metadata first
        val metadata = extractMetadata(context, uri, fileName, format)

        // Multi-tier decode
        var decodedBitmap: Bitmap? = null

        // Tier 0: Direct Bayer CFA & SubIFD color extraction for DNG (Leica DNG, Adobe DNG)
        // Bypasses in-camera B&W picture styles and thumbnail previews
        if (format == RawFormat.DNG) {
            try {
                decodedBitmap = DngColorExtractor.extractColorBitmap(context, uri)
                if (decodedBitmap != null) {
                    Log.i(TAG, "DngColorExtractor successfully decoded authentic sensor CFA color data.")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "DngColorExtractor initial pass: ${e.message}")
            }
        }

        // Tier 1: Try modern Android ImageDecoder (API 28+)
        if (decodedBitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                decodedBitmap = decodeWithImageDecoder(context, uri)
            } catch (e: Throwable) {
                Log.w(TAG, "ImageDecoder failed for $uri: ${e.message}. Falling back to preview stream extraction.")
            }
        }

        // Tier 2: Extract embedded JPEG from RAW (ExifInterface thumbnail or embedded IFD preview)
        if (decodedBitmap == null && format.isRaw) {
            try {
                decodedBitmap = extractRawEmbeddedPreview(context, uri)
            } catch (e: Throwable) {
                Log.w(TAG, "Embedded preview extraction failed: ${e.message}")
            }
        }

        // Tier 3: Scan byte stream for Sony ARW / DNG embedded JPEG SOI
        if (decodedBitmap == null && format == RawFormat.SONY_ARW) {
            try {
                decodedBitmap = extractSonyArwJpegStream(context, uri)
            } catch (e: Throwable) {
                Log.w(TAG, "Sony ARW stream scan failed: ${e.message}")
            }
        }

        // Tier 4: Fallback to standard BitmapFactory.decodeStream
        if (decodedBitmap == null) {
            decodedBitmap = decodeWithBitmapFactory(context, uri)
        }

        if (decodedBitmap == null) {
            throw IllegalStateException("Failed to decode image from $uri (Format: ${format.formatName})")
        }

        // Tier 5: Rotate if orientation requires it and ImageDecoder did not automatically orient
        if (metadata.orientationDegrees != 0) {
            decodedBitmap = applyOrientation(decodedBitmap, metadata.orientationDegrees)
        }

        // Tier 6: Automatic Color Recovery for RAW files shot with in-camera B&W / Monochrome picture profiles
        // If the RAW image was captured with B&W style enabled on camera, recover real original colors!
        var isColorRecovered = false
        if (format.isRaw && RawColorRecoveryEngine.isMonochrome(decodedBitmap)) {
            Log.i(TAG, "RAW photo (${metadata.displayCameraName}) is monochrome (shot with in-camera B&W profile). Recovering original sensor colors...")
            val (colorBitmap, recovered) = RawColorRecoveryEngine.recoverRealColors(context, uri, decodedBitmap, metadata)
            decodedBitmap = colorBitmap
            isColorRecovered = recovered
        }

        val finalMetadata = metadata.copy(
            originalWidth = if (metadata.originalWidth > 0) metadata.originalWidth else decodedBitmap.width,
            originalHeight = if (metadata.originalHeight > 0) metadata.originalHeight else decodedBitmap.height,
            isColorRecovered = isColorRecovered,
            colorSpace = if (isColorRecovered) "Restored Full Sensor Color (B&W Bypassed)" else metadata.colorSpace
        )

        RawDecodeResult(bitmap = decodedBitmap, metadata = finalMetadata)
    }

    /**
     * Decodes using Android P+ ImageDecoder with memory-safe target sample sizing.
     */
    private fun decodeWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Prevent massive OOM allocations on 61MP Sony A7R or large DNG images
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
    }

    /**
     * Extracts embedded JPEG thumbnail or high-res preview from RAW file via ExifInterface.
     */
    private fun extractRawEmbeddedPreview(context: Context, uri: Uri): Bitmap? {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            if (exif.hasThumbnail()) {
                val thumbBytes = exif.thumbnailBytes
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    }
                    return BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size, opts)
                }
            }
        }
        return null
    }

    /**
     * Scans Sony ARW stream for embedded full-resolution or 1616x1080 JPEG preview stream.
     * Sony Alpha cameras embed a standard JPEG image payload starting with SOI marker (0xFF, 0xD8).
     */
    private fun extractSonyArwJpegStream(context: Context, uri: Uri): Bitmap? {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(1024 * 1024 * 8) // Up to 8MB preview buffer
            var bytesRead: Int
            val out = ByteArrayOutputStream()

            var count = 0
            while (count < 8 * 1024 * 1024) {
                val b = inputStream.read()
                if (b == -1) break
                out.write(b)
                count++
            }
            val data = out.toByteArray()

            // Look for JPEG SOI: 0xFF, 0xD8, 0xFF
            var jpegStart = -1
            for (i in 0 until data.size - 3) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                    jpegStart = i
                    break
                }
            }

            if (jpegStart != -1) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                return BitmapFactory.decodeByteArray(data, jpegStart, data.size - jpegStart, opts)
            }
        }
        return null
    }

    /**
     * Decodes using standard BitmapFactory with inSampleSize bounds calculation.
     */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        var sampleSize = 1
        val maxDim = max(boundsOptions.outWidth, boundsOptions.outHeight)
        if (maxDim > MAX_DECODE_DIMENSION) {
            sampleSize = maxDim / MAX_DECODE_DIMENSION
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }

        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    /**
     * Extracts photographic and EXIF telemetry from the image URI.
     */
    fun extractMetadata(
        context: Context,
        uri: Uri,
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
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)

                make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
                    ?: exif.getAttribute("LensModel")?.trim()

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
                    1 -> "sRGB (Rec.709)"
                    2 -> "Adobe RGB"
                    else -> if (format.isRaw) "Camera Native RAW Gamut" else "sRGB"
                }

                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                orientationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading EXIF metadata for $uri: ${e.message}")
        }

        // If make is missing but Sony ARW format detected, default make to SONY
        if (make == null && format == RawFormat.SONY_ARW) {
            make = "SONY"
        }

        val isLeica = make?.contains("Leica", ignoreCase = true) == true ||
                model?.contains("Leica", ignoreCase = true) == true

        val sensorDesc = when {
            format == RawFormat.SONY_ARW -> "35mm Full-Frame BSI Exmor R CMOS • 14-bit ARW"
            isLeica -> "Leica Full-Frame Maestro • Full Dynamic Color DNG"
            format == RawFormat.DNG -> "Adobe DNG • Linear/Bayer CFA Negative"
            else -> "Standard 8-bit RGB Scan"
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
            orientationDegrees = orientationDegrees,
            sensorDescription = sensorDesc
        )
    }

    private fun applyOrientation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
