package com.example.film

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One geometry path for the preview, comparison and exported copy. */
object PhotoRenderer {
    private const val TILE_EDGE = 512
    private const val MAX_EXPORT_PIXELS = 32_000_000L
    private const val SIZE_ERROR = "This photo is too large for a full-size export on this device. Choose Smaller copy."

    fun dimensions(width: Int, height: Int, crop: CropSettings, maxLongEdge: Int? = null): Pair<Int, Int> {
        val geometry = geometry(width, height, crop, maxLongEdge)
        return geometry.width to geometry.height
    }

    fun withAspect(width: Int, height: Int, crop: CropSettings, aspect: CropAspect): CropSettings {
        require(width > 0 && height > 0)
        val normalized = crop.normalized()
        if (aspect == CropAspect.FREE) return normalized.copy(aspect = aspect)
        val w = if (normalized.quarterTurns % 2 == 0) width.toFloat() else height.toFloat()
        val h = if (normalized.quarterTurns % 2 == 0) height.toFloat() else width.toFloat()
        val target = when (aspect) {
            CropAspect.SQUARE -> 1f
            CropAspect.FOUR_FIVE -> 4f / 5f
            CropAspect.SIXTEEN_NINE -> 16f / 9f
            else -> w / h
        }
        val frameW = (normalized.right - normalized.left) * w
        val frameH = (normalized.bottom - normalized.top) * h
        val newW = min(frameW, frameH * target) / w
        val newH = min(frameH, frameW / target) / h
        val centerX = (normalized.left + normalized.right) / 2f
        val centerY = (normalized.top + normalized.bottom) / 2f
        return normalized.copy(
            left = centerX - newW / 2f, right = centerX + newW / 2f,
            top = centerY - newH / 2f, bottom = centerY + newH / 2f, aspect = aspect
        ).normalized()
    }

    fun cropOriginal(base: Bitmap, crop: CropSettings): Bitmap {
        val settings = crop.normalized()
        if (settings.left == 0f && settings.top == 0f && settings.right == 1f && settings.bottom == 1f &&
            settings.quarterTurns == 0 && settings.straightenDegrees == 0f) return base
        val geometry = geometry(base.width, base.height, settings)
        return opaqueBitmap(geometry.width, geometry.height).apply {
            Canvas(this).drawBitmap(base, geometry.matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    suspend fun renderPreview(base: Bitmap, settings: EditSettings): Bitmap {
        var completed: Bitmap? = null
        try {
            return withContext(Dispatchers.Default) {
                val edit = settings.normalized()
                val cropped = cropOriginal(base, edit.crop)
                try {
                    val result = renderEffect(cropped, edit)
                    if (cropped !== base && cropped !== result) cropped.recycle()
                    result.also { completed = it }
                } catch (error: Throwable) {
                    if (cropped !== base) cropped.recycle()
                    throw error
                }
            }
        } catch (error: Throwable) {
            if (completed !== base && completed?.isRecycled == false) completed?.recycle()
            throw error
        }
    }

    /** Decodes only the source region needed by each tile; memory is output + bounded working tiles. */
    suspend fun renderSource(file: File, settings: EditSettings, maxLongEdge: Int? = null): Bitmap {
        var completed: Bitmap? = null
        try {
            return withContext(Dispatchers.Default) {
                check(file.isFile) { "The original photo is unavailable. Reopen it to save a larger copy." }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                check(options.outWidth > 0 && options.outHeight > 0) {
                    "This format cannot be rendered at full size on this device. Choose Smaller copy."
                }
                val orientation = runCatching {
                    ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val orientedWidth = if (orientation in 5..8) options.outHeight else options.outWidth
                val orientedHeight = if (orientation in 5..8) options.outWidth else options.outHeight
                val edit = settings.normalized()
                val geometry = geometry(orientedWidth, orientedHeight, edit.crop, maxLongEdge)
                val pixels = geometry.width.toLong() * geometry.height
                val outputBudget = (Runtime.getRuntime().maxMemory() * .4).toLong()
                require(pixels <= MAX_EXPORT_PIXELS && pixels * 4 <= outputBudget) { SIZE_ERROR }
                val region = try {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(file.absolutePath, false)
                } catch (error: Exception) {
                    throw IllegalStateException("This format cannot be rendered at full size on this device. Choose Smaller copy.", error)
                } ?: throw IllegalStateException("This format cannot be rendered at full size on this device. Choose Smaller copy.")
                val nativeToOutput = orientationMatrix(options.outWidth, options.outHeight, orientation).apply {
                    postConcat(geometry.matrix)
                }
                val outputToNative = Matrix()
                check(nativeToOutput.invert(outputToNative)) { "The selected crop could not be rendered." }
                val resolutionScale = max(1f, max(orientedWidth, orientedHeight) / FilmEngine.TARGET_LONG_EDGE.toFloat()) * geometry.scale
                val alignment = FilmEngine.tileAlignment(resolutionScale)
                val halo = FilmEngine.requiredHalo(edit.preset, edit.glowIntensity, resolutionScale)
                var output: Bitmap? = null
                try {
                    output = opaqueBitmap(geometry.width, geometry.height)
                    val canvas = Canvas(output)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    var sample = 1
                    while (sample * 2 <= 1f / geometry.scale) sample *= 2
                    for (top in 0 until geometry.height step TILE_EDGE) {
                        for (left in 0 until geometry.width step TILE_EDGE) {
                            currentCoroutineContext().ensureActive()
                            val right = min(left + TILE_EDGE, geometry.width)
                            val bottom = min(top + TILE_EDGE, geometry.height)
                            val padded = Rect(
                                max(0, floor((left - halo).toDouble() / alignment).toInt() * alignment),
                                max(0, floor((top - halo).toDouble() / alignment).toInt() * alignment),
                                min(geometry.width, ceil((right + halo).toDouble() / alignment).toInt() * alignment),
                                min(geometry.height, ceil((bottom + halo).toDouble() / alignment).toInt() * alignment)
                            )
                            val nativeBounds = RectF(padded)
                            outputToNative.mapRect(nativeBounds)
                            val nativeRect = Rect(
                                ((floor(nativeBounds.left.toDouble() / sample).toInt() - 2) * sample).coerceIn(0, options.outWidth - 1),
                                ((floor(nativeBounds.top.toDouble() / sample).toInt() - 2) * sample).coerceIn(0, options.outHeight - 1),
                                ((ceil(nativeBounds.right.toDouble() / sample).toInt() + 2) * sample).coerceIn(1, options.outWidth),
                                ((ceil(nativeBounds.bottom.toDouble() / sample).toInt() + 2) * sample).coerceIn(1, options.outHeight)
                            )
                            val decoded = region.decodeRegion(nativeRect, BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }) ?: throw IllegalStateException("The original photo could not be decoded.")
                            var tile: Bitmap? = null
                            var rendered: Bitmap? = null
                            try {
                                tile = opaqueBitmap(padded.width(), padded.height())
                                val matrix = Matrix(nativeToOutput).apply {
                                    preTranslate(nativeRect.left.toFloat(), nativeRect.top.toFloat())
                                    preScale(sample.toFloat(), sample.toFloat())
                                    postTranslate(-padded.left.toFloat(), -padded.top.toFloat())
                                }
                                Canvas(tile).drawBitmap(decoded, matrix, paint)
                                decoded.recycle()
                                rendered = renderEffect(tile, edit, padded.left, padded.top, resolutionScale)
                                canvas.drawBitmap(rendered, Rect(left - padded.left, top - padded.top, right - padded.left, bottom - padded.top),
                                    Rect(left, top, right, bottom), paint)
                            } finally {
                                if (!decoded.isRecycled) decoded.recycle()
                                if (rendered !== tile) rendered?.recycle()
                                if (tile != null && !tile.isRecycled) tile.recycle()
                            }
                        }
                    }
                    output.also { completed = it }
                } catch (error: OutOfMemoryError) {
                    output?.recycle()
                    throw IllegalArgumentException(SIZE_ERROR, error)
                } catch (error: Throwable) {
                    output?.recycle()
                    throw error
                } finally {
                    region.recycle()
                }
            }
        } catch (error: Throwable) {
            if (completed?.isRecycled == false) completed?.recycle()
            throw error
        }
    }

    private suspend fun renderEffect(bitmap: Bitmap, edit: EditSettings, originX: Int = 0, originY: Int = 0, scale: Float = 1f): Bitmap {
        val adjusted = FilmEngine.applyAdjustments(bitmap, edit.exposure, edit.warmth)
        try {
            val result = FilmEngine.applyFilmFilter(adjusted, edit.preset, edit.strength, edit.grainIntensity,
                glowIntensity = edit.glowIntensity, originX = originX, originY = originY, resolutionScale = scale)
            if (adjusted !== bitmap && adjusted !== result) adjusted.recycle()
            return result
        } catch (error: Throwable) {
            if (adjusted !== bitmap) adjusted.recycle()
            throw error
        }
    }

    private fun opaqueBitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(FilmEngine.PHOTO_MATTE_COLOR)
        setHasAlpha(false)
    }

    private data class Geometry(val width: Int, val height: Int, val scale: Float, val matrix: Matrix)

    private fun geometry(width: Int, height: Int, crop: CropSettings, maxLongEdge: Int? = null): Geometry {
        require(width > 0 && height > 0) { "Photo dimensions must be positive." }
        require(maxLongEdge == null || maxLongEdge > 0)
        val normalized = crop.normalized()
        val matrix = Matrix().apply { setRotate(normalized.quarterTurns * 90f) }
        val rotatedBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        matrix.mapRect(rotatedBounds)
        matrix.postTranslate(-rotatedBounds.left, -rotatedBounds.top)
        val w = if (normalized.quarterTurns % 2 == 0) width.toFloat() else height.toFloat()
        val h = if (normalized.quarterTurns % 2 == 0) height.toFloat() else width.toFloat()
        matrix.postRotate(normalized.straightenDegrees, w / 2f, h / 2f)
        val radians = Math.toRadians(normalized.straightenDegrees.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        val safeScale = min(w / (w * c + h * s), h / (h * c + w * s)).coerceAtMost(1f)
        val frame = RectF(
            w * (.5f + (normalized.left - .5f) * safeScale),
            h * (.5f + (normalized.top - .5f) * safeScale),
            w * (.5f + (normalized.right - .5f) * safeScale),
            h * (.5f + (normalized.bottom - .5f) * safeScale)
        )
        val scale = if (maxLongEdge == null) 1f else min(1f, maxLongEdge / max(frame.width(), frame.height()))
        val targetW = (frame.width() * scale).roundToInt().coerceAtLeast(1)
        val targetH = (frame.height() * scale).roundToInt().coerceAtLeast(1)
        matrix.postTranslate(-frame.left, -frame.top)
        matrix.postScale(targetW / frame.width(), targetH / frame.height())
        return Geometry(targetW, targetH, scale, matrix)
    }

    private fun orientationMatrix(width: Int, height: Int, orientation: Int): Matrix {
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        return matrix
    }
}
