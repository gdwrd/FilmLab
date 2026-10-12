package com.example.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "DngColorExtractor"
private const val MAX_DIMENSION = 2560

/** Reads supported TIFF sensor data or embedded JPEG previews. */
object DngColorExtractor {

    data class ExtractedBitmap(val bitmap: Bitmap, val isSensorData: Boolean)

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
     * Attempts to decode sensor data or a JPEG preview from a DNG file.
     */
    fun extractColorBitmap(file: File): Bitmap? {
        return extractWithSource(file)?.bitmap
    }

    fun extractWithSource(file: File): ExtractedBitmap? {
        if (!file.exists() || file.length() < 16) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                parseDngFileForColor(raf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractColorBitmap(file) error: ${e.message}")
            null
        }
    }

    /**
     * Attempts to decode sensor data or a JPEG preview from a DNG URI.
     */
    fun extractColorBitmap(context: Context, uri: Uri): Bitmap? {
        return extractWithSource(context, uri)?.bitmap
    }

    fun extractWithSource(context: Context, uri: Uri): ExtractedBitmap? {
        return try {
            val tempFile = File.createTempFile("dng_extract_", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    tempFile.outputStream().use { out -> stream.copyTo(out) }
                } ?: return null
                extractWithSource(tempFile)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractColorBitmap(uri) error: ${e.message}")
            null
        }
    }

    /**
     * Attempts to decode sensor data or a JPEG preview from DNG bytes.
     */
    fun extractColorBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.size < 16) return null
        return try {
            val byteOrder = when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return null
            }

            val buffer = ByteBuffer.wrap(bytes).order(byteOrder)
            val magic = buffer.getShort(2).toInt() and 0xFFFF
            if (magic != 42) return null

            val ifdOffset = buffer.getInt(4).toLong() and 0xFFFFFFFFL
            val subIfdOffsets = scanSubIfdOffsets(buffer, ifdOffset, bytes.size)

            for (subOffset in subIfdOffsets) {
                if (subOffset !in 8 until bytes.size - 2) continue
                val subInfo = readSubIfdInfo(buffer, subOffset, bytes.size) ?: continue

                // 1. Uncompressed CFA Bayer Sensor Data (Leica DNG, uncompressed Adobe DNG)
                if (subInfo.photometric == 32803 && subInfo.compression == 1 && subInfo.stripOffsets.isNotEmpty()) {
                    val demosaiced = demosaicCfa(bytes, subInfo)
                    if (demosaiced != null) return demosaiced
                }

                // 2. High-res JPEG SubIFD
                if (subInfo.compression == 7 && subInfo.stripOffsets.isNotEmpty()) {
                    val offset = subInfo.stripOffsets[0].toInt()
                    val length = if (subInfo.stripByteCounts.isNotEmpty()) subInfo.stripByteCounts[0].toInt() else bytes.size - offset
                    if (offset in 0 until bytes.size && length > 10000 && offset + length <= bytes.size) {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bmp = BitmapFactory.decodeByteArray(bytes, offset, length, opts)
                        if (bmp != null) return bmp
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "extractColorBitmap(bytes) error: ${e.message}")
            null
        }
    }

    private fun parseDngFileForColor(raf: RandomAccessFile): ExtractedBitmap? {
        val fileLen = raf.length()
        if (fileLen < 16) return null

        raf.seek(0)
        val header = ByteArray(8)
        raf.readFully(header)

        val byteOrder = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }

        val headerBuf = ByteBuffer.wrap(header).order(byteOrder)
        val magic = headerBuf.getShort(2).toInt() and 0xFFFF
        if (magic != 42) return null

        val ifd0Offset = headerBuf.getInt(4).toLong() and 0xFFFFFFFFL
        if (ifd0Offset !in 8 until fileLen - 2) return null

        // Read IFD0 and locate SubIFDs
        val subIfdOffsets = mutableListOf<Long>()
        raf.seek(ifd0Offset)
        val entryCount = readShort(raf, byteOrder)
        for (i in 0 until entryCount) {
            val entryPos = ifd0Offset + 2 + i * 12
            if (entryPos + 12 > fileLen) break
            raf.seek(entryPos)
            val tag = readShort(raf, byteOrder)
            val type = readShort(raf, byteOrder)
            val count = readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL
            val valOrOff = readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL

            if (tag == 0x014A) { // SubIFDs
                if (count == 1L) {
                    subIfdOffsets.add(valOrOff)
                } else if (valOrOff in 0 until (fileLen - count * 4)) {
                    raf.seek(valOrOff)
                    for (k in 0 until min(count, 32L)) {
                        subIfdOffsets.add(readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL)
                    }
                }
            }
        }

        // Search SubIFDs
        for (subOffset in subIfdOffsets) {
            if (subOffset !in 8 until fileLen - 2) continue
            val subInfo = readSubIfdInfoFromRaf(raf, subOffset, fileLen, byteOrder) ?: continue

            // Strategy 1: Uncompressed CFA Bayer Sensor Data (Leica DNG full sensor array)
            if (subInfo.photometric == 32803 && subInfo.compression == 1 && subInfo.stripOffsets.isNotEmpty()) {
                val demosaiced = demosaicCfaFromRaf(raf, subInfo, fileLen)
                if (demosaiced != null) {
                    Log.i(TAG, "Successfully demosaiced CFA sensor data from SubIFD at offset $subOffset (${demosaiced.width}x${demosaiced.height})")
                    return ExtractedBitmap(demosaiced, isSensorData = true)
                }
            }

            // Strategy 2: High-res JPEG SubIFD (e.g. >= 800px)
            if (subInfo.compression == 7 && subInfo.stripOffsets.isNotEmpty()) {
                val offset = subInfo.stripOffsets[0]
                val length = if (subInfo.stripByteCounts.isNotEmpty()) subInfo.stripByteCounts[0] else (fileLen - offset)
                if (offset in 0 until fileLen && length > 50000 && offset + length <= fileLen) {
                    raf.seek(offset)
                    val jpegBytes = ByteArray(length.toInt().coerceAtMost(16 * 1024 * 1024))
                    raf.readFully(jpegBytes)
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        val maxDim = max(subInfo.width, subInfo.height)
                        if (maxDim > MAX_DIMENSION) {
                            inSampleSize = (maxDim / MAX_DIMENSION).coerceAtLeast(1)
                        }
                    }
                    val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                    if (bmp != null && (bmp.width >= 600 || bmp.height >= 600)) {
                        Log.i(TAG, "Decoded high-res SubIFD preview (${bmp.width}x${bmp.height})")
                        return ExtractedBitmap(bmp, isSensorData = false)
                    }
                }
            }
        }

        return null
    }

    private fun scanSubIfdOffsets(buffer: ByteBuffer, ifdOffset: Long, maxLen: Int): List<Long> {
        val offsets = mutableListOf<Long>()
        if (ifdOffset !in 8 until maxLen - 2) return offsets
        val count = buffer.getShort(ifdOffset.toInt()).toInt() and 0xFFFF
        var entryPos = ifdOffset + 2
        for (i in 0 until count) {
            if (entryPos + 12 > maxLen) break
            val tag = buffer.getShort(entryPos.toInt()).toInt() and 0xFFFF
            val valCount = buffer.getInt((entryPos + 4).toInt()).toLong() and 0xFFFFFFFFL
            val valOrOff = buffer.getInt((entryPos + 8).toInt()).toLong() and 0xFFFFFFFFL

            if (tag == 0x014A) {
                if (valCount == 1L) {
                    offsets.add(valOrOff)
                } else if (valOrOff in 0 until (maxLen - valCount * 4)) {
                    for (k in 0 until min(valCount, 32L)) {
                        offsets.add(buffer.getInt((valOrOff + k * 4).toInt()).toLong() and 0xFFFFFFFFL)
                    }
                }
            }
            entryPos += 12
        }
        return offsets
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
        var cfaPattern = intArrayOf(0, 1, 1, 2)

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
                0x828E -> {
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

    private fun readSubIfdInfoFromRaf(raf: RandomAccessFile, ifdOffset: Long, maxLen: Long, byteOrder: ByteOrder): SubIfdInfo? {
        if (ifdOffset + 2 > maxLen) return null
        raf.seek(ifdOffset)
        val count = readShort(raf, byteOrder)

        var width = 0
        var height = 0
        var bits = 14
        var compression = 1
        var photometric = 0
        val offsets = mutableListOf<Long>()
        val byteCounts = mutableListOf<Long>()
        var cfaPattern = intArrayOf(0, 1, 1, 2)

        for (i in 0 until count) {
            val entryPos = ifdOffset + 2 + i * 12
            if (entryPos + 12 > maxLen) break
            raf.seek(entryPos)
            val tag = readShort(raf, byteOrder)
            val type = readShort(raf, byteOrder)
            val valCount = readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL
            val valOrOff = readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL

            when (tag) {
                0x0100 -> width = valOrOff.toInt()
                0x0101 -> height = valOrOff.toInt()
                0x0102 -> bits = valOrOff.toInt()
                0x0103 -> compression = valOrOff.toInt()
                0x0106 -> photometric = valOrOff.toInt()
                0x0111 -> {
                    if (valCount == 1L) offsets.add(valOrOff)
                    else if (valOrOff in 0 until (maxLen - valCount * 4)) {
                        raf.seek(valOrOff)
                        for (k in 0 until min(valCount, 128L)) {
                            offsets.add(readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL)
                        }
                    }
                }
                0x0117 -> {
                    if (valCount == 1L) byteCounts.add(valOrOff)
                    else if (valOrOff in 0 until (maxLen - valCount * 4)) {
                        raf.seek(valOrOff)
                        for (k in 0 until min(valCount, 128L)) {
                            byteCounts.add(readInt(raf, byteOrder).toLong() and 0xFFFFFFFFL)
                        }
                    }
                }
                0x828E -> {
                    if (valCount >= 4L && valOrOff in 0 until (maxLen - 4)) {
                        raf.seek(valOrOff)
                        cfaPattern = intArrayOf(
                            raf.read() and 0xFF,
                            raf.read() and 0xFF,
                            raf.read() and 0xFF,
                            raf.read() and 0xFF
                        )
                    }
                }
            }
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

    private fun demosaicCfaFromRaf(raf: RandomAccessFile, info: SubIfdInfo, fileLen: Long): Bitmap? {
        val origW = info.width
        val origH = info.height
        if (origW < 100 || origH < 100) return null

        val startOffset = info.stripOffsets[0]
        if (startOffset !in 0 until fileLen) return null

        val maxDim = max(origW, origH)
        val step = max(1, (maxDim / MAX_DIMENSION))
        val bayerStep = if (step % 2 != 0 && step > 1) step + 1 else step

        val outW = origW / bayerStep
        val outH = origH / bayerStep
        if (outW <= 0 || outH <= 0) return null

        val is16Bit = info.bitsPerSample >= 12
        val bytesPerPixel = if (is16Bit) 2 else 1
        val rowStride = origW * bytesPerPixel

        val pixels = IntArray(outW * outH)
        val rowBuffer = ByteArray(rowStride * 2)

        for (y in 0 until outH) {
            val srcY = (y * bayerStep).coerceIn(0, origH - 2)
            val rowOffsetInFile = startOffset + srcY.toLong() * rowStride
            if (rowOffsetInFile + rowBuffer.size > fileLen) break

            raf.seek(rowOffsetInFile)
            raf.read(rowBuffer)

            for (x in 0 until outW) {
                val srcX = (x * bayerStep).coerceIn(0, origW - 2)

                val c00 = readSampleFromBuffer(rowBuffer, srcX, 0, rowStride, is16Bit)
                val c10 = readSampleFromBuffer(rowBuffer, srcX + 1, 0, rowStride, is16Bit)
                val c01 = readSampleFromBuffer(rowBuffer, srcX, 1, rowStride, is16Bit)
                val c11 = readSampleFromBuffer(rowBuffer, srcX + 1, 1, rowStride, is16Bit)

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

                val rGamma = gammaCorrect(r)
                val gGamma = gammaCorrect(g)
                val bGamma = gammaCorrect(b)

                pixels[y * outW + x] = Color.rgb(rGamma, gGamma, bGamma)
            }
        }

        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun demosaicCfa(bytes: ByteArray, info: SubIfdInfo): Bitmap? {
        val origW = info.width
        val origH = info.height
        if (origW < 100 || origH < 100) return null

        val startOffset = info.stripOffsets[0].toInt()
        if (startOffset !in 0 until bytes.size) return null

        val maxDim = max(origW, origH)
        val step = max(1, (maxDim / MAX_DIMENSION))
        val bayerStep = if (step % 2 != 0 && step > 1) step + 1 else step

        val outW = origW / bayerStep
        val outH = origH / bayerStep
        if (outW <= 0 || outH <= 0) return null

        val is16Bit = info.bitsPerSample >= 12
        val bytesPerPixel = if (is16Bit) 2 else 1
        val rowStride = origW * bytesPerPixel

        val pixels = IntArray(outW * outH)

        for (y in 0 until outH) {
            val srcY = (y * bayerStep).coerceIn(0, origH - 2)
            for (x in 0 until outW) {
                val srcX = (x * bayerStep).coerceIn(0, origW - 2)

                val c00 = readSample(bytes, startOffset, srcX, srcY, rowStride, is16Bit)
                val c10 = readSample(bytes, startOffset, srcX + 1, srcY, rowStride, is16Bit)
                val c01 = readSample(bytes, startOffset, srcX, srcY + 1, rowStride, is16Bit)
                val c11 = readSample(bytes, startOffset, srcX + 1, srcY + 1, rowStride, is16Bit)

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

                val rGamma = gammaCorrect(r)
                val gGamma = gammaCorrect(g)
                val bGamma = gammaCorrect(b)

                pixels[y * outW + x] = Color.rgb(rGamma, gGamma, bGamma)
            }
        }

        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun readSampleFromBuffer(
        buffer: ByteArray,
        x: Int,
        row: Int,
        stride: Int,
        is16Bit: Boolean
    ): Int {
        val pos = row * stride + x * (if (is16Bit) 2 else 1)
        if (pos + 1 >= buffer.size || pos < 0) return 128

        return if (is16Bit) {
            val low = buffer[pos].toInt() and 0xFF
            val high = buffer[pos + 1].toInt() and 0xFF
            val raw16 = (high shl 8) or low
            (raw16 shr 6).coerceIn(0, 1023)
        } else {
            (buffer[pos].toInt() and 0xFF) * 4
        }
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
            (raw16 shr 6).coerceIn(0, 1023)
        } else {
            (bytes[pos].toInt() and 0xFF) * 4
        }
    }

    private fun gammaCorrect(v1024: Int): Int {
        val norm = (v1024 / 1024.0).coerceIn(0.0, 1.0)
        val corrected = Math.pow(norm, 0.50) * 255.0
        return corrected.roundToInt().coerceIn(0, 255)
    }

    private fun readShort(raf: RandomAccessFile, byteOrder: ByteOrder): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            (b1 shl 8) or b0
        } else {
            (b0 shl 8) or b1
        } and 0xFFFF
    }

    private fun readInt(raf: RandomAccessFile, byteOrder: ByteOrder): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }
}
