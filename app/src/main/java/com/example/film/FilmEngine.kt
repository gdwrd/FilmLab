package com.example.film

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object FilmEngine {

    /** Editing preview size; export scales texture and glow to retain the same appearance. */
    const val TARGET_LONG_EDGE = 2048
    const val PHOTO_MATTE_COLOR = 0xFF111112.toInt()

    // Pre-computed lookup tables for instantaneous, artifact-free tone transformations
    private val kodachromeRedLut = IntArray(256)
    private val kodachromeGreenLut = IntArray(256)
    private val kodachromeBlueLut = IntArray(256)

    private val portraRedLut = IntArray(256)
    private val portraGreenLut = IntArray(256)
    private val portraBlueLut = IntArray(256)

    private val velviaRedLut = IntArray(256)
    private val velviaGreenLut = IntArray(256)
    private val velviaBlueLut = IntArray(256)

    private val cinestillRedLut = IntArray(256)
    private val cinestillGreenLut = IntArray(256)
    private val cinestillBlueLut = IntArray(256)

    private val pro400hRedLut = IntArray(256)
    private val pro400hGreenLut = IntArray(256)
    private val pro400hBlueLut = IntArray(256)

    private val goldRedLut = IntArray(256)
    private val goldGreenLut = IntArray(256)
    private val goldBlueLut = IntArray(256)
    private val ektachromeToneLut = IntArray(256)
    private val deltaToneLut = IntArray(256)

    private val hp5ToneLut = IntArray(256)
    private val triXToneLut = IntArray(256)

    init {
        buildLookupTables()
    }

    private fun buildLookupTables() {
        for (i in 0..255) {
            val x = i / 255.0

            // -------------------------------------------------------------
            // 1. KODACHROME 64 (Reversal Slide Film, K-14 Process)
            // -------------------------------------------------------------
            val sCurveKoda = if (x < 0.5) {
                0.5 * (2.0 * x).pow(1.26)
            } else {
                1.0 - 0.5 * (2.0 * (1.0 - x)).pow(1.26)
            }
            val rWarmKoda = (sCurveKoda + 0.035 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            kodachromeRedLut[i] = (rWarmKoda * 255.0).toInt().coerceIn(0, 255)

            val gBalancedKoda = (sCurveKoda + 0.010 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            kodachromeGreenLut[i] = (gBalancedKoda * 255.0).toInt().coerceIn(0, 255)

            val shadowCoolLiftKoda = if (x < 0.20) 0.018 * (1.0 - x / 0.20) else 0.0
            val bCurveKoda = (sCurveKoda + shadowCoolLiftKoda).coerceIn(0.0, 1.0)
            kodachromeBlueLut[i] = (bCurveKoda * 255.0).toInt().coerceIn(0, 255)

            // -------------------------------------------------------------
            // 2. KODAK PORTRA 400 (C-41 Professional Color Negative)
            // Lifted toe (orange mask pedestal, D-min never crushed to 0)
            // Extended linear latitude + soft highlight compression shoulder
            // -------------------------------------------------------------
            val portraBaseCurve = if (x < 0.40) {
                0.042 + 0.38 * (x / 0.40).pow(1.12)
            } else {
                0.422 + 0.578 * ((x - 0.40) / 0.60).pow(0.92)
            }
            // Gentle peachy warmth in red, restrained blue
            val portraR = (portraBaseCurve + 0.024 * sin(x * Math.PI * 0.9)).coerceIn(0.0, 1.0)
            val portraG = (portraBaseCurve + 0.008 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            val portraB = (portraBaseCurve - 0.016 * sin(x * Math.PI * 0.85)).coerceIn(0.0, 1.0)
            portraRedLut[i] = (portraR * 255.0).toInt().coerceIn(0, 255)
            portraGreenLut[i] = (portraG * 255.0).toInt().coerceIn(0, 255)
            portraBlueLut[i] = (portraB * 255.0).toInt().coerceIn(0, 255)

            // -------------------------------------------------------------
            // 3. FUJIFILM VELVIA 50 (E-6 Ultra-Vivid Chrome Slide)
            // High gamma (1.68), inky D-Max blacks, explosive micro-contrast
            // -------------------------------------------------------------
            val sCurveVelvia = if (x < 0.5) {
                0.5 * (2.0 * x).pow(1.68)
            } else {
                1.0 - 0.5 * (2.0 * (1.0 - x)).pow(1.68)
            }
            val velviaR = (sCurveVelvia + 0.018 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            val velviaG = (sCurveVelvia + 0.025 * sin(x * Math.PI * 1.1)).coerceIn(0.0, 1.0)
            val velviaB = (sCurveVelvia + 0.012 * sin(x * Math.PI * 0.8)).coerceIn(0.0, 1.0)
            velviaRedLut[i] = (velviaR * 255.0).toInt().coerceIn(0, 255)
            velviaGreenLut[i] = (velviaG * 255.0).toInt().coerceIn(0, 255)
            velviaBlueLut[i] = (velviaB * 255.0).toInt().coerceIn(0, 255)

            // -------------------------------------------------------------
            // 4. CINESTILL 800T (Tungsten 3200K Motion Picture Vision3 500T)
            // Cool cyan/teal shadows, wide 14-stop motion latitude
            // -------------------------------------------------------------
            val cinestillBase = if (x < 0.35) {
                0.038 + 0.32 * (x / 0.35).pow(1.18)
            } else {
                0.358 + 0.642 * ((x - 0.35) / 0.65).pow(0.95)
            }
            // Tungsten cooling: depressed red in shadows, lifted blue/teal
            val csShadowBias = (1.0 - x).coerceIn(0.0, 1.0)
            val csR = (cinestillBase - 0.032 * csShadowBias + 0.012 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            val csG = (cinestillBase + 0.006 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            val csB = (cinestillBase + 0.048 * csShadowBias).coerceIn(0.0, 1.0)
            cinestillRedLut[i] = (csR * 255.0).toInt().coerceIn(0, 255)
            cinestillGreenLut[i] = (csG * 255.0).toInt().coerceIn(0, 255)
            cinestillBlueLut[i] = (csB * 255.0).toInt().coerceIn(0, 255)

            // -------------------------------------------------------------
            // 5. FUJIFILM PRO 400H (C-41 4th-Layer Cyan Negative)
            // Airy high-key lifted shadows, soft contrast, mint/lavender tones
            // -------------------------------------------------------------
            val pro400hBase = if (x < 0.30) {
                0.055 + 0.30 * (x / 0.30).pow(1.08)
            } else {
                0.355 + 0.645 * ((x - 0.30) / 0.70).pow(0.96)
            }
            val proShadowWeight = (1.0 - x).pow(1.4).coerceIn(0.0, 1.0)
            val proR = (pro400hBase - 0.018 * proShadowWeight).coerceIn(0.0, 1.0)
            val proG = (pro400hBase + 0.014 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            val proB = (pro400hBase + 0.024 * proShadowWeight).coerceIn(0.0, 1.0)
            pro400hRedLut[i] = (proR * 255.0).toInt().coerceIn(0, 255)
            pro400hGreenLut[i] = (proG * 255.0).toInt().coerceIn(0, 255)
            pro400hBlueLut[i] = (proB * 255.0).toInt().coerceIn(0, 255)

            // -------------------------------------------------------------
            // 6. ILFORD HP5 PLUS (400 ISO Panchromatic B&W)
            // Balanced classic British push curve with silvery highlights
            // -------------------------------------------------------------
            val hp5Curve = if (x < 0.45) {
                (x / 0.45).pow(1.4) * 0.38
            } else {
                0.38 + ((x - 0.45) / 0.55).pow(0.88) * 0.62
            }.coerceIn(0.0, 1.0)
            hp5ToneLut[i] = (hp5Curve * 255.0).toInt().coerceIn(0, 255)

            // -------------------------------------------------------------
            // 7. KODAK TRI-X 400 (D-76 Developer Photojournalism B&W)
            // Steep midtone contrast S-curve with deep charcoal blacks
            // -------------------------------------------------------------
            val triXCurve = if (x < 0.50) {
                0.50 * (x / 0.50).pow(1.62)
            } else {
                0.50 + 0.50 * ((x - 0.50) / 0.50).pow(0.82)
            }.coerceIn(0.0, 1.0)
            triXToneLut[i] = (triXCurve * 255.0).toInt().coerceIn(0, 255)

            // Artistic interpretations: warm everyday colour, neutral slides and fine monochrome.
            val goldBase = 0.015 + 0.985 * x.pow(1.04)
            goldRedLut[i] = ((goldBase + 0.040 * sin(x * Math.PI)) * 255).roundToInt().coerceIn(0, 255)
            goldGreenLut[i] = ((goldBase + 0.012 * sin(x * Math.PI)) * 255).roundToInt().coerceIn(0, 255)
            goldBlueLut[i] = ((goldBase - 0.030 * sin(x * Math.PI)) * 255).roundToInt().coerceIn(0, 255)
            val slideCurve = if (x < .5) .5 * (2 * x).pow(1.18) else 1 - .5 * (2 * (1 - x)).pow(1.18)
            ektachromeToneLut[i] = (slideCurve * 255).roundToInt().coerceIn(0, 255)
            val deltaCurve = if (x < .5) .5 * (2 * x).pow(1.12) else 1 - .5 * (2 * (1 - x)).pow(1.12)
            deltaToneLut[i] = (deltaCurve * 255).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Limits the long edge to the calibrated 2048px working resolution without enlarging small photos,
     * preserving the original aspect ratio and flattening transparency for JPEG output.
     */
    fun normalizeTo35mmResolution(source: Bitmap): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height

        val (targetWidth, targetHeight) = calculate35mmDimensions(srcWidth, srcHeight)

        if (!source.hasAlpha()) {
            if (srcWidth == targetWidth && srcHeight == targetHeight) return source
            return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }

        // A single opaque matte keeps preview and JPEG blending identical at every strength.
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(PHOTO_MATTE_COLOR)
            Canvas(this).drawBitmap(source, null, Rect(0, 0, targetWidth, targetHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            setHasAlpha(false)
        }
    }

    fun calculate35mmDimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Photo dimensions must be positive" }
        if (max(width, height) <= TARGET_LONG_EDGE) return Pair(width, height)
        return if (width >= height) {
            val h = max(1, (height.toLong() * TARGET_LONG_EDGE / width).toInt())
            Pair(TARGET_LONG_EDGE, h)
        } else {
            val w = max(1, (width.toLong() * TARGET_LONG_EDGE / height).toInt())
            Pair(w, TARGET_LONG_EDGE)
        }
    }

    /** Exposure is applied in linear light before the film's tone curve. */
    suspend fun applyAdjustments(bitmap: Bitmap, exposure: Float = 0f, warmth: Float = 0f): Bitmap = withContext(Dispatchers.Default) {
        val ev = (exposure.takeIf(Float::isFinite) ?: 0f).coerceIn(-2f, 2f)
        val balance = (warmth.takeIf(Float::isFinite) ?: 0f).coerceIn(-1f, 1f)
        if (ev == 0f && balance == 0f) return@withContext bitmap
        val red = adjustmentLut(2.0.pow(ev + balance * .35))
        val green = adjustmentLut(2.0.pow(ev.toDouble()))
        val blue = adjustmentLut(2.0.pow(ev - balance * .35))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until bitmap.width) {
                val index = y * bitmap.width + x
                val pixel = pixels[index]
                pixels[index] = (pixel and -0x1000000) or
                    (red[(pixel ushr 16) and 255] shl 16) or
                    (green[(pixel ushr 8) and 255] shl 8) or blue[pixel and 255]
            }
        }
        Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
            setHasAlpha(bitmap.hasAlpha())
        }
    }

    private fun adjustmentLut(multiplier: Double) = IntArray(256) { value ->
        val srgb = value / 255.0
        val linear = (if (srgb <= .04045) srgb / 12.92 else ((srgb + .055) / 1.055).pow(2.4)) * multiplier
        val result = if (linear <= .0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - .055
        (result * 255).roundToInt().coerceIn(0, 255)
    }

    fun tileAlignment(resolutionScale: Float = 1f): Int {
        require(resolutionScale.isFinite() && resolutionScale > 0) { "Photo scale must be positive" }
        return (4 * resolutionScale.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)
    }

    /** Two neighbouring highlight cells plus a full partial cell keep tile edges invisible. */
    fun requiredHalo(preset: FilmPreset, glowIntensity: Float = 1f, resolutionScale: Float = 1f): Int =
        if (preset == FilmPreset.CINESTILL_800T && (glowIntensity.takeIf(Float::isFinite) ?: 1f) > 0f) 3 * tileAlignment(resolutionScale) else 0

    private data class Coordinates(val originX: Int, val originY: Int, val scale: Float) {
        fun x(value: Int) = floor((originX.toDouble() + value) / scale).toInt()
        fun y(value: Int) = floor((originY.toDouble() + value) / scale).toInt()
    }

    private fun grainHash(x: Int, y: Int, salt: Int): Int {
        var value = x * 374761393 + y * 668265263 + salt
        value = (value xor (value ushr 13)) * 1274126177
        return value xor (value ushr 16)
    }

    /** The global coordinates keep grain and highlight glow continuous across export tiles. */
    suspend fun applyFilmFilter(
        baseBitmap: Bitmap,
        preset: FilmPreset,
        intensity: Float = 1.0f,
        grainIntensity: Float = 1.0f,
        glowIntensity: Float = 1.0f,
        originX: Int = 0,
        originY: Int = 0,
        resolutionScale: Float = 1.0f
    ): Bitmap = withContext(Dispatchers.Default) {
        require(resolutionScale.isFinite() && resolutionScale > 0) { "Photo scale must be positive" }
        val clampedIntensity = (intensity.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f)
        if (preset == FilmPreset.ORIGINAL || clampedIntensity <= 0f) return@withContext baseBitmap
        val clampedGrainIntensity = (grainIntensity.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 3f)
        val clampedGlow = (glowIntensity.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f)
        val coordinates = Coordinates(originX, originY, resolutionScale.coerceAtLeast(1f))
        val width = baseBitmap.width
        val height = baseBitmap.height
        val srcPixels = IntArray(width * height)
        baseBitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val dstPixels = IntArray(width * height)
        val halation = if (preset == FilmPreset.CINESTILL_800T && clampedGlow > 0) {
            computeHalationBloomMap(srcPixels, width, height, originX, originY, tileAlignment(resolutionScale))
        } else null
        val numCores = Runtime.getRuntime().availableProcessors().coerceIn(2, height.coerceAtLeast(2))
        val chunkHeight = (height + numCores - 1) / numCores
        (0 until numCores).map { coreIndex ->
            async {
                val startY = coreIndex * chunkHeight
                val endY = min(height, startY + chunkHeight)
                if (startY < endY) {
                    when (preset) {
                        FilmPreset.KODACHROME_64 -> processKodachromeChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates)
                        FilmPreset.PORTRA_400 -> processPortraChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates)
                        FilmPreset.FUJI_VELVIA_50 -> processVelviaChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates)
                        FilmPreset.CINESTILL_800T -> processCineStillChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, halation, clampedGrainIntensity, clampedGlow, coordinates)
                        FilmPreset.FUJI_PRO_400H -> processPro400hChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates)
                        FilmPreset.ILFORD_HP5 -> processIlfordHp5Chunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates)
                        FilmPreset.KODAK_TRI_X -> processTriXChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates)
                        FilmPreset.GOLD_200, FilmPreset.ILFORD_DELTA_100, FilmPreset.EKTACHROME_E100 -> processNewFilmChunk(srcPixels, dstPixels, width, startY, endY, clampedIntensity, clampedGrainIntensity, coordinates, preset)
                        FilmPreset.ORIGINAL -> Unit
                    }
                }
            }
        }.awaitAll()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(dstPixels, 0, width, 0, 0, width, height)
            setHasAlpha(baseBitmap.hasAlpha())
        }
    }

    private data class BloomMap(val pixels: FloatArray, val width: Int, val firstX: Int, val firstY: Int, val block: Int) {
        fun at(x: Int, y: Int): Float = pixels[(Math.floorDiv(y, block) - firstY) * width + Math.floorDiv(x, block) - firstX]
    }

    private suspend fun computeHalationBloomMap(src: IntArray, width: Int, height: Int, originX: Int, originY: Int, block: Int): BloomMap {
        val firstX = Math.floorDiv(originX, block)
        val firstY = Math.floorDiv(originY, block)
        val dsW = Math.floorDiv(originX + width - 1, block) - firstX + 1
        val dsH = Math.floorDiv(originY + height - 1, block) - firstY + 1
        val highlights = FloatArray(dsW * dsH)
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            val blockRow = (Math.floorDiv(originY + y, block) - firstY) * dsW
            for (x in 0 until width) {
                val pixel = src[y * width + x]
                val lum = (((pixel ushr 16) and 255) * 299 + ((pixel ushr 8) and 255) * 587 + (pixel and 255) * 114) / 1000
                if (lum > 212) {
                    val index = blockRow + Math.floorDiv(originX + x, block) - firstX
                    highlights[index] = max(highlights[index], (lum - 212f) / 43f)
                }
            }
        }
        val horizontal = FloatArray(dsW * dsH)
        val result = FloatArray(dsW * dsH)
        val weights = floatArrayOf(.07f, .24f, .38f, .24f, .07f)
        for (y in 0 until dsH) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until dsW) {
                for (offset in -2..2) if (x + offset in 0 until dsW) {
                    horizontal[y * dsW + x] += highlights[y * dsW + x + offset] * weights[offset + 2]
                }
            }
        }
        for (y in 0 until dsH) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until dsW) {
                for (offset in -2..2) if (y + offset in 0 until dsH) {
                    result[y * dsW + x] += horizontal[(y + offset) * dsW + x] * weights[offset + 2]
                }
            }
        }
        return BloomMap(result, dsW, firstX, firstY, block)
    }

    // =========================================================================
    // 1. KODACHROME 64 (Reversal Slide Film, K-14 Process)
    // =========================================================================
    private suspend fun processKodachromeChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                var filmR = kodachromeRedLut[r].toFloat()
                var filmG = kodachromeGreenLut[g].toFloat()
                var filmB = kodachromeBlueLut[b].toFloat()

                // K-14 Tri-Pack Spectral Dye Coupling
                if (b > r) {
                    val blueDominance = (b - r).toFloat()
                    filmR = (filmR - blueDominance * 0.28f).coerceAtLeast(0f)
                    filmG = (filmG - blueDominance * 0.03f).coerceAtLeast(0f)
                    filmB = (filmB + blueDominance * 0.08f).coerceAtMost(255f)
                } else {
                    val warmDominance = (r - b).toFloat()
                    filmR = (filmR + warmDominance * 0.12f).coerceAtMost(255f)
                    filmG = (filmG + warmDominance * 0.03f).coerceAtMost(255f)
                    filmB = (filmB - warmDominance * 0.06f).coerceAtLeast(0f)
                }

                // 5500K daylight highlight ivory glow
                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f
                if (lum > 195f) {
                    val neutralWeight = (1.0f - abs(r - b) / 90f).coerceAtLeast(0f)
                    val highlightWeight = ((lum - 195f) / 60f).coerceIn(0f, 1f)
                    val warmGlow = highlightWeight * neutralWeight * 7f
                    filmR = (filmR + warmGlow).coerceAtMost(255f)
                    filmG = (filmG + warmGlow * 0.45f).coerceAtMost(255f)
                    filmB = (filmB - warmGlow * 0.55f).coerceAtLeast(0f)
                }

                // Fine organic dye cloud grain (ISO 64)
                val midtoneWeight = (1.0f - abs(lum - 128f) / 128f).coerceAtLeast(0f)
                val rngState = grainHash(coordinates.x(x), grainY, 1013904223)

                val noiseVal = ((rngState and 0x7FFF) % 25) - 12
                val dyeGrain = noiseVal * midtoneWeight * 0.48f * grainIntensity

                filmR = (filmR + dyeGrain).coerceIn(0f, 255f)
                filmG = (filmG + dyeGrain).coerceIn(0f, 255f)
                filmB = (filmB + dyeGrain).coerceIn(0f, 255f)

                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    // =========================================================================
    // 2. KODAK PORTRA 400 (C-41 Professional Color Negative)
    // =========================================================================
    private suspend fun processPortraChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                var filmR = portraRedLut[r].toFloat()
                var filmG = portraGreenLut[g].toFloat()
                var filmB = portraBlueLut[b].toFloat()

                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f

                // Skin tone melanin calibration:
                // When R > G and G > B (human skin spectrum), optimize warmth and reduce harsh magenta
                if (r > g && g > b && lum in 65f..225f) {
                    val skinWeight = ((g - b).toFloat() / 70f).coerceIn(0f, 1f)
                    filmR = (filmR + skinWeight * 5.5f).coerceAtMost(255f)
                    filmG = (filmG + skinWeight * 2.8f).coerceAtMost(255f)
                    filmB = (filmB - skinWeight * 3.5f).coerceAtLeast(0f)
                }

                // Scanned C-41 shadow base: delicate cool slate-olive tint in deep shadows
                if (lum < 75f) {
                    val shadowLift = ((75f - lum) / 75f)
                    filmG = (filmG + shadowLift * 3.0f).coerceAtMost(255f)
                    filmB = (filmB + shadowLift * 4.5f).coerceAtMost(255f)
                }

                // Kodak T-GRAIN (Tabular grain) simulation:
                // Silky, uniform micro-structure with minimal clumping
                val midtoneWeight = (1.0f - abs(lum - 128f) / 128f).coerceAtLeast(0f).pow(1.3f)
                val rngState = grainHash(coordinates.x(x), grainY, 1729481)

                val noiseVal = ((rngState and 0x7FFF) % 19) - 9
                val tGrain = noiseVal * midtoneWeight * 0.42f * grainIntensity

                filmR = (filmR + tGrain).coerceIn(0f, 255f)
                filmG = (filmG + tGrain).coerceIn(0f, 255f)
                filmB = (filmB + tGrain).coerceIn(0f, 255f)

                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    // =========================================================================
    // 3. FUJIFILM VELVIA 50 (E-6 Ultra-Vivid Chrome Slide)
    // =========================================================================
    private suspend fun processVelviaChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                var filmR = velviaRedLut[r].toFloat()
                var filmG = velviaGreenLut[g].toFloat()
                var filmB = velviaBlueLut[b].toFloat()

                // Iconic Velvia Emerald Green Pop (Chlorophyll / Foliage spectrum):
                if (g > r && g > b) {
                    val greenDominance = (g - max(r, b)).toFloat()
                    filmG = (filmG + greenDominance * 0.32f).coerceAtMost(255f)
                    filmR = (filmR - greenDominance * 0.22f).coerceAtLeast(0f)
                    filmB = (filmB + greenDominance * 0.08f).coerceAtMost(255f) // Rich emerald cyan touch
                }

                // Deep Slide Cerulean/Navy Sky:
                if (b > r) {
                    val skyDominance = (b - r).toFloat()
                    filmR = (filmR - skyDominance * 0.34f).coerceAtLeast(0f)
                    filmB = (filmB + skyDominance * 0.14f).coerceAtMost(255f)
                }

                // Vibrant Sunset Reds & Magentas:
                if (r > g && r > b) {
                    val redDominance = (r - max(g, b)).toFloat()
                    filmR = (filmR + redDominance * 0.18f).coerceAtMost(255f)
                    filmG = (filmG - redDominance * 0.08f).coerceAtLeast(0f)
                }

                // Microscopic ISO 50 slide dye grain (ultra-fine)
                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f
                val midtoneWeight = (1.0f - abs(lum - 128f) / 128f).coerceAtLeast(0f)
                val rngState = grainHash(coordinates.x(x), grainY, 2491823)

                val noiseVal = ((rngState and 0x7FFF) % 11) - 5
                val microGrain = noiseVal * midtoneWeight * 0.28f * grainIntensity

                filmR = (filmR + microGrain).coerceIn(0f, 255f)
                filmG = (filmG + microGrain).coerceIn(0f, 255f)
                filmB = (filmB + microGrain).coerceIn(0f, 255f)

                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    // =========================================================================
    // 4. CINESTILL 800T (Tungsten 3200K Motion Picture & Red Halation)
    // =========================================================================
    private suspend fun processCineStillChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float,
        halationMap: BloomMap?, grainIntensity: Float, glowIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                var filmR = cinestillRedLut[r].toFloat()
                var filmG = cinestillGreenLut[g].toFloat()
                var filmB = cinestillBlueLut[b].toFloat()

                if (halationMap != null) {
                    val bloom = halationMap.at(coordinates.originX + x, coordinates.originY + y)
                    if (bloom > 0.04f) {
                        filmR = (filmR + bloom * 88f * glowIntensity).coerceAtMost(255f)
                        filmG = (filmG + bloom * 16f * glowIntensity).coerceAtMost(255f)
                        filmB = (filmB + bloom * 5f * glowIntensity).coerceAtMost(255f)
                    }
                }

                // High-ISO 800 motion picture dye cloud grain
                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f
                val grainWeight = if (lum < 140f) {
                    (lum / 140f).pow(0.8f)
                } else {
                    ((255f - lum) / 115f).coerceAtLeast(0f)
                }

                val rngState = grainHash(coordinates.x(x), grainY, 3184917)

                val noiseVal = ((rngState and 0x7FFF) % 35) - 17
                val cineGrain = noiseVal * grainWeight * 0.72f * grainIntensity

                filmR = (filmR + cineGrain).coerceIn(0f, 255f)
                filmG = (filmG + cineGrain).coerceIn(0f, 255f)
                filmB = (filmB + cineGrain).coerceIn(0f, 255f)

                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    // =========================================================================
    // 5. FUJIFILM PRO 400H (C-41 4th-Layer Cyan Negative)
    // =========================================================================
    private suspend fun processPro400hChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                var filmR = pro400hRedLut[r].toFloat()
                var filmG = pro400hGreenLut[g].toFloat()
                var filmB = pro400hBlueLut[b].toFloat()

                // 4th Cyan Layer Response:
                // Softens yellowish greens into clean mint/sage
                if (g > b && g > r) {
                    val foliageDiff = (g - r).toFloat()
                    filmG = (filmG + foliageDiff * 0.08f).coerceAtMost(255f)
                    filmB = (filmB + foliageDiff * 0.16f).coerceAtMost(255f) // Fresh mint bias
                    filmR = (filmR - foliageDiff * 0.10f).coerceAtLeast(0f)
                }

                // Pastel Bridal/Porcelain skin tone calibration:
                // Subtle softening of saturated red flush
                if (r > g && g > b) {
                    val flushDiff = (r - g).toFloat()
                    filmR = (filmR - flushDiff * 0.10f).coerceAtLeast(0f)
                    filmG = (filmG + flushDiff * 0.05f).coerceAtMost(255f)
                    filmB = (filmB + flushDiff * 0.06f).coerceAtMost(255f)
                }

                // Fine negative dye clouds
                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f
                val midtoneWeight = (1.0f - abs(lum - 128f) / 128f).coerceAtLeast(0f)

                val rngState = grainHash(coordinates.x(x), grainY, 1883921)

                val noiseVal = ((rngState and 0x7FFF) % 23) - 11
                val proGrain = noiseVal * midtoneWeight * 0.44f * grainIntensity

                filmR = (filmR + proGrain).coerceIn(0f, 255f)
                filmG = (filmG + proGrain).coerceIn(0f, 255f)
                filmB = (filmB + proGrain).coerceIn(0f, 255f)

                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    // =========================================================================
    // 6. ILFORD HP5 PLUS (400 ISO Panchromatic B&W)
    // =========================================================================
    private suspend fun processIlfordHp5Chunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                // Panchromatic weighted luminance (0.32 R + 0.54 G + 0.14 B)
                val monoLum = ((r * 328 + g * 553 + b * 143) shr 10).coerceIn(0, 255)
                val filmVal = hp5ToneLut[monoLum]

                // Silver halide grain physics (Zones IV through VII)
                val grainWeight = if (filmVal < 120) {
                    (filmVal / 120f).pow(1.1f)
                } else {
                    ((255 - filmVal) / 135f).coerceAtLeast(0f).pow(0.85f)
                }.coerceIn(0f, 1f)

                val rngState = grainHash(coordinates.x(x), grainY, 1664525)

                val noiseVal = ((rngState and 0x7FFF) % 43) - 21
                val grainDelta = (noiseVal * grainWeight * 1.35f * grainIntensity).toInt()
                val finalMono = (filmVal + grainDelta).coerceIn(0, 255)

                val outR = (r + (finalMono - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (finalMono - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (finalMono - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    // =========================================================================
    // 7. KODAK TRI-X 400 (D-76 Developer Photojournalism B&W)
    // =========================================================================
    private suspend fun processTriXChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            val rowOffset = y * width
            val blockY = grainY shr 1

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                // Ortho-panchromatic luminance (0.38 R + 0.52 G + 0.10 B)
                // Naturally darkens blue skies and boosts facial/architectural micro-contrast
                val monoLum = ((r * 389 + g * 532 + b * 103) shr 10).coerceIn(0, 255)
                val filmVal = triXToneLut[monoLum]

                val grainWeight = if (filmVal < 135) {
                    (filmVal / 135f).pow(0.95f)
                } else {
                    ((255 - filmVal) / 120f).coerceAtLeast(0f).pow(0.75f)
                }.coerceIn(0f, 1f)

                // High-speed XorShift random step for base crystal
                val rngState = grainHash(coordinates.x(x), grainY, 2938171)

                val crystalNoise = ((rngState and 0x7FFF) % 39) - 19

                // Spatial 2x2 clumping hash for tactile metallic silver filaments (D-76 grit)
                val blockX = coordinates.x(x) shr 1
                val clumpHash = (blockX * 374761393 + blockY * 668265263) xor 0x5BF03635
                val clumpNoise = ((clumpHash and 0x7FFF) % 25) - 12

                val totalNoise = (crystalNoise * 0.65f + clumpNoise * 0.35f)
                val grainDelta = (totalNoise * grainWeight * 1.55f * grainIntensity).toInt()
                val finalMono = (filmVal + grainDelta).coerceIn(0, 255)

                val outR = (r + (finalMono - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (finalMono - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (finalMono - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    private suspend fun processNewFilmChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int,
        intensity: Float, grainIntensity: Float, coordinates: Coordinates, preset: FilmPreset
    ) {
        for (y in startY until endY) {
            currentCoroutineContext().ensureActive()
            val grainY = coordinates.y(y)
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = src[index]
                val r = (pixel ushr 16) and 255
                val g = (pixel ushr 8) and 255
                val b = pixel and 255
                var filmR: Float
                var filmG: Float
                var filmB: Float
                val grainAmount: Float
                when (preset) {
                    FilmPreset.GOLD_200 -> {
                        filmR = goldRedLut[r].toFloat()
                        filmG = goldGreenLut[g].toFloat()
                        filmB = goldBlueLut[b].toFloat()
                        grainAmount = 2.4f
                    }
                    FilmPreset.ILFORD_DELTA_100 -> {
                        val luminance = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
                        filmR = deltaToneLut[luminance].toFloat()
                        filmG = filmR
                        filmB = filmR
                        grainAmount = 1.8f
                    }
                    FilmPreset.EKTACHROME_E100 -> {
                        filmR = ektachromeToneLut[r].toFloat()
                        filmG = ektachromeToneLut[g].toFloat()
                        filmB = ektachromeToneLut[b].toFloat()
                        val luminance = filmR * .299f + filmG * .587f + filmB * .114f
                        filmR = luminance + (filmR - luminance) * 1.06f
                        filmG = luminance + (filmG - luminance) * 1.06f
                        filmB = luminance + (filmB - luminance) * 1.06f
                        grainAmount = 1.4f
                    }
                    else -> error("Unexpected film preset")
                }
                val luminance = filmR * .299f + filmG * .587f + filmB * .114f
                val midtoneWeight = (1f - abs(luminance - 128f) / 128f).coerceIn(0f, 1f)
                val noise = ((grainHash(coordinates.x(x), grainY, preset.ordinal * 827 + 27183) and 0x7FFF) / 16383.5f - 1f) * grainAmount * grainIntensity * midtoneWeight
                filmR = (filmR + noise).coerceIn(0f, 255f)
                filmG = (filmG + noise).coerceIn(0f, 255f)
                filmB = (filmB + noise).coerceIn(0f, 255f)
                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)
                dst[index] = (pixel and -0x1000000) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

}
