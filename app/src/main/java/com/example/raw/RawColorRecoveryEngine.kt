package com.example.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "RawColorRecoveryEngine"

/** Recovers colour only when the file contains decodable colour sensor data. */
object RawColorRecoveryEngine {

    /**
     * Inspects whether a bitmap is purely or substantially monochrome (R ~= G ~= B everywhere).
     */
    fun isMonochrome(bitmap: Bitmap, sampleCount: Int = 400): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false

        var totalChroma = 0.0
        var tested = 0
        val stepX = max(1, bitmap.width / 20)
        val stepY = max(1, bitmap.height / 20)

        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                val diff = abs(r - g) + abs(g - b) + abs(b - r)
                totalChroma += diff
                tested++
                if (tested >= sampleCount) break
            }
            if (tested >= sampleCount) break
        }

        val avgChroma = if (tested > 0) totalChroma / tested else 0.0
        return avgChroma < 3.5 // Less than 3.5 average color deviation across channels = B&W
    }

    /** Leaves monochrome pixels unchanged when sensor colour cannot be decoded. */
    fun recoverRealColors(
        context: Context,
        uri: Uri? = null,
        file: File? = null,
        decodedBitmap: Bitmap,
        metadata: RawMetadata
    ): Pair<Bitmap, Boolean> {
        // If image is already full color, preserve it
        if (!isMonochrome(decodedBitmap)) {
            return Pair(decodedBitmap, false)
        }

        Log.i(TAG, "RAW image is monochrome; checking stored sensor data.")

        // Phase 1: Direct CFA Demosaic from DNG file bytes (recovers raw Bayer photodiode colors)
        if (metadata.format == RawFormat.DNG) {
            try {
                val extracted = when {
                    file != null -> DngColorExtractor.extractWithSource(file)
                    uri != null -> DngColorExtractor.extractWithSource(context, uri)
                    else -> null
                }
                if (extracted != null && extracted.isSensorData && !isMonochrome(extracted.bitmap)) {
                    Log.i(TAG, "Decoded colour from DNG sensor data.")
                    return Pair(extracted.bitmap, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct CFA extraction skipped: ${e.message}")
            }
        }

        return Pair(decodedBitmap, false)
    }

    fun recoverRealColors(
        context: Context,
        uri: Uri,
        decodedBitmap: Bitmap,
        metadata: RawMetadata
    ): Pair<Bitmap, Boolean> = recoverRealColors(context, uri, null, decodedBitmap, metadata)

}
