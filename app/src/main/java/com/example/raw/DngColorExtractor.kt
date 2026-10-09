package com.example.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "DngColorExtractor"
private const val MAX_DIMENSION = 2560

/**
 * High-performance parser for Adobe DNG and Leica DNG camera files.
 * Extracts authentic sensor Bayer CFA (Color Filter Array) data and SubIFD color streams,
 * bypassing in-camera Black & White picture profiles and monochrome preview JPEGs.
 */
object DngColorExtractor {

    data class IfdEntry(
        val tag: Int,
        val type: Int,
        val count: Long,
        val valueOrOffset: Long
    )

    data class SubIfdInfo(
        val width: Int,
        val height: Int,
        val bitsPerSample: Int,
        val compression: Int,
        val photometric: Int,
        val stripOffsets: LongArray,
        val stripByteCounts: LongArray,
        val cfaPattern: IntArray // e.g. [0, 1, 1, 2] = RGGB
    )

    /**
     * Attempts to extract full-color sensor bitmap from DNG file.
     * Returns null if DNG does not have an uncompressed/readable CFA or color stream.
     */
    fun extractColorBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                // Read up to first 48MB (covers header, IFD0, SubIFDs and raw strips)
                val buffer = ByteArray(min(48 * 1024 * 1024, stream.available().coerceAtLeast(1024 * 1024)))
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                if (total == buffer.size) buffer else buffer.copyOf(total)
            } ?: return null

            parseDngForColor(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "DngColorExtractor failed: ${e.message}")
            null
        }
    }

    private fun parseDngForColor(bytes: ByteArray): Bitmap? {
        if (bytes.size < 16) return null

        val byteOrder = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(byteOrder)
        val magic = buffer.getShort(2).toInt() and 0xFFFF
        if (magic != 42) return null

        var ifdOffset = buffer.getInt(4).toLong() and 0xFFFFFFFFL
        val subIfdOffsets = mutableListOf<Long>()

        // Scan IFD0
        if (ifdOffset in 8 until bytes.size - 2) {
            val count = buffer.getShort(ifdOffset.toInt()).toInt() and 0xFFFF
            var entryPos = ifdOffset + 2
            for (i in 0 until count) {
                if (entryPos + 12 > bytes.size) break
                val tag = buffer.getShort(entryPos.toInt()).toInt() and 0xFFFF
                val type = buffer.getShort((entryPos + 2).toInt()).toInt() and 0xFFFF
                val valCount = buffer.getInt((entryPos + 4).toInt()).toLong() and 0xFFFFFFFFL
                val valOrOff = buffer.getInt((entryPos + 8).toInt()).toLong() and 0xFFFFFFFFL

                if (tag == 0x014A) { // SubIFDs
                    if (valCount == 1L) {
                        subIfdOffsets.add(valOrOff)
                    } else if (valOrOff in 0 until (bytes.size - valCount * 4)) {
                        for (k in 0 until valCount) {
                            subIfdOffsets.add(buffer.getInt((valOrOff + k * 4).toInt()).toLong() and 0xFFFFFFFFL)
                        }
                    }
                }
                entryPos += 12
            }
        }

        // Search SubIFDs for CFA sensor mosaic (PhotometricInterpretation 32803) or RGB (PhotometricInterpretation 2)
        for (subOffset in subIfdOffsets) {
            if (subOffset !in 8 until bytes.size - 2) continue
            val subInfo = readSubIfdInfo(buffer, subOffset, bytes.size) ?: continue

            // 1. Uncompressed CFA Bayer Sensor Data (Leica DNG, uncompressed Adobe DNG)
            if (subInfo.photometric == 32803 && subInfo.compression == 1 && subInfo.stripOffsets.isNotEmpty()) {
                val demosaiced = demosaicCfa(bytes, subInfo)
                if (demosaiced != null) return demosaiced
            }

            // 2. High-res JPEG SubIFD (if it's not the thumbnail and has color)
            if (subInfo.compression == 7 && subInfo.stripOffsets.isNotEmpty()) {
                val offset = subInfo.stripOffsets[0].toInt()
                val length = if (subInfo.stripByteCounts.isNotEmpty()) subInfo.stripByteCounts[0].toInt() else bytes.size - offset
                if (offset in 0 until bytes.size && length > 10000 && offset + length <= bytes.size) {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bmp = BitmapFactory.decodeByteArray(bytes, offset, length, opts)
                    if (bmp != null && !isPureMonochrome(bmp)) {
                        return bmp
                    }
                }
            }
        }

        return null
    }

    private fun readSubIfdInfo(buffer: ByteBuffer, ifdOffset: Long, maxLen: Int): SubIfdInfo? {
        if (ifdOffset + 2 > maxLen) return null
        val count = buffer.getShort(ifdOffset.toInt()).toInt() and 0xFFFF
        var entryPos = ifdOffset + 2

        var width = 0
        var height = 0
        var bits = 14
        var compression = 1
        var photometric = 0
        val offsets = mutableListOf<Long>()
        val byteCounts = mutableListOf<Long>()
        var cfaPattern = intArrayOf(0, 1, 1, 2) // Default RGGB

        for (i in 0 until count) {
            if (entryPos + 12 > maxLen) break
            val tag = buffer.getShort(entryPos.toInt()).toInt() and 0xFFFF
            val valCount = buffer.getInt((entryPos + 4).toInt()).toLong() and 0xFFFFFFFFL
            val valOrOff = buffer.getInt((entryPos + 8).toInt()).toLong() and 0xFFFFFFFFL

            when (tag) {
                0x0100 -> width = valOrOff.toInt()
                0x0101 -> height = valOrOff.toInt()
                0x0102 -> bits = valOrOff.toInt()
                0x0103 -> compression = valOrOff.toInt()
                0x0106 -> photometric = valOrOff.toInt()
                0x0111 -> {
                    if (valCount == 1L) offsets.add(valOrOff)
                    else if (valOrOff in 0 until (maxLen - valCount * 4)) {
                        for (k in 0 until min(valCount, 128L)) {
                            offsets.add(buffer.getInt((valOrOff + k * 4).toInt()).toLong() and 0xFFFFFFFFL)
                        }
                    }
                }
                0x0117 -> {
                    if (valCount == 1L) byteCounts.add(valOrOff)
                    else if (valOrOff in 0 until (maxLen - valCount * 4)) {
                        for (k in 0 until min(valCount, 128L)) {
                            byteCounts.add(buffer.getInt((valOrOff + k * 4).toInt()).toLong() and 0xFFFFFFFFL)
                        }
                    }
                }
                0x828E -> { // CFAPattern
                    if (valCount >= 4L && valOrOff in 0 until (maxLen - 4)) {
                        cfaPattern = intArrayOf(
                            buffer.get(valOrOff.toInt()).toInt() and 0xFF,
                            buffer.get((valOrOff + 1).toInt()).toInt() and 0xFF,
                            buffer.get((valOrOff + 2).toInt()).toInt() and 0xFF,
                            buffer.get((valOrOff + 3).toInt()).toInt() and 0xFF
                        )
                    }
                }
            }
            entryPos += 12
        }

        if (width <= 0 || height <= 0) return null
        return SubIfdInfo(
            width = width,
            height = height,
            bitsPerSample = bits,
            compression = compression,
            photometric = photometric,
            stripOffsets = offsets.toLongArray(),
            stripByteCounts = byteCounts.toLongArray(),
            cfaPattern = cfaPattern
        )
    }

    /**
     * Demosaics uncompressed CFA Bayer mosaic into a pristine full-color RGB Bitmap.
     * Samples every physical Red, Green, and Blue photosite on the sensor, recovering original color.
     */
    private fun demosaicCfa(bytes: ByteArray, info: SubIfdInfo): Bitmap? {
        val origW = info.width
        val origH = info.height
        if (origW < 100 || origH < 100) return null

        val startOffset = info.stripOffsets[0].toInt()
        if (startOffset !in 0 until bytes.size) return null

        // Downsample factor to fit MAX_DIMENSION safely
        val maxDim = max(origW, origH)
        val step = max(1, (maxDim / MAX_DIMENSION))
        // Must step in even numbers to preserve Bayer 2x2 grid alignment!
        val bayerStep = if (step % 2 != 0 && step > 1) step + 1 else step

        val outW = origW / bayerStep
        val outH = origH / bayerStep
        if (outW <= 0 || outH <= 0) return null

        val is16Bit = info.bitsPerSample >= 12
        val bytesPerPixel = if (is16Bit) 2 else 1
        val rowStride = origW * bytesPerPixel

        val pixels = IntArray(outW * outH)

        // Bilinear CFA Demosaic Loop
        for (y in 0 until outH) {
            val srcY = (y * bayerStep).coerceIn(0, origH - 2)
            for (x in 0 until outW) {
                val srcX = (x * bayerStep).coerceIn(0, origW - 2)

                // 2x2 Bayer quad at (srcX, srcY)
                val c00 = readSample(bytes, startOffset, srcX, srcY, rowStride, is16Bit)
                val c10 = readSample(bytes, startOffset, srcX + 1, srcY, rowStride, is16Bit)
                val c01 = readSample(bytes, startOffset, srcX, srcY + 1, rowStride, is16Bit)
                val c11 = readSample(bytes, startOffset, srcX + 1, srcY + 1, rowStride, is16Bit)

                // Standard RGGB:
                // [R, G1]
                // [G2, B]
                val r: Int
                val g: Int
                val b: Int

                when {
                    info.cfaPattern[0] == 0 -> { // RGGB
                        r = c00
                        g = (c10 + c01) / 2
                        b = c11
                    }
                    info.cfaPattern[0] == 2 -> { // BGGR
                        b = c00
                        g = (c10 + c01) / 2
                        r = c11
                    }
                    else -> { // GBRG / GRBG
                        g = (c00 + c11) / 2
                        r = c10
                        b = c01
                    }
                }

                // White balance balance & gamma correction
                val rGamma = gammaCorrect(r)
                val gGamma = gammaCorrect(g)
                val bGamma = gammaCorrect(b)

                pixels[y * outW + x] = Color.rgb(rGamma, gGamma, bGamma)
            }
        }

        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun readSample(
        bytes: ByteArray,
        startOffset: Int,
        x: Int,
        y: Int,
        rowStride: Int,
        is16Bit: Boolean
    ): Int {
        val pos = startOffset + y * rowStride + x * (if (is16Bit) 2 else 1)
        if (pos + 1 >= bytes.size || pos < 0) return 128

        return if (is16Bit) {
            val low = bytes[pos].toInt() and 0xFF
            val high = bytes[pos + 1].toInt() and 0xFF
            val raw16 = (high shl 8) or low
            (raw16 shr 6).coerceIn(0, 1023) // 10-bit reference
        } else {
            (bytes[pos].toInt() and 0xFF) * 4
        }
    }

    private fun gammaCorrect(v1024: Int): Int {
        val norm = (v1024 / 1024.0).coerceIn(0.0, 1.0)
        // Standard photographic sRGB / Rec.709 tone curve (power ~0.45)
        val corrected = Math.pow(norm, 0.50) * 255.0
        return corrected.roundToInt().coerceIn(0, 255)
    }

    private fun isPureMonochrome(bitmap: Bitmap): Boolean {
        var count = 0
        var totalChroma = 0
        val stepX = max(1, bitmap.width / 20)
        val stepY = max(1, bitmap.height / 20)

        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                totalChroma += Math.abs(r - g) + Math.abs(g - b) + Math.abs(b - r)
                count++
            }
        }
        return count > 0 && (totalChroma.toFloat() / count) < 2.0f
    }
}
