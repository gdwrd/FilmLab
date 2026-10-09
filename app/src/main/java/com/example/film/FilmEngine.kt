package com.example.film

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object FilmEngine {

    /**
     * Standard 35mm optical lab scan resolution (long edge).
     * At 2048px, a single pixel represents ~17.5 micrometers of a 36mm film frame,
     * ensuring that physical grain clump simulation is resolution-consistent
     * whether the original photo was shot on a 12MP or 200MP sensor.
     */
    const val TARGET_LONG_EDGE = 2048

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
        }
    }

    /**
     * Normalizes any input bitmap to the calibrated 35mm long edge resolution (2048px),
     * preserving the original aspect ratio.
     */
    fun normalizeTo35mmResolution(source: Bitmap): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height

        val (targetWidth, targetHeight) = calculate35mmDimensions(srcWidth, srcHeight)

        if (srcWidth == targetWidth && srcHeight == targetHeight) {
            return source
        }

        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    fun calculate35mmDimensions(width: Int, height: Int): Pair<Int, Int> {
        return if (width >= height) {
            val h = max(1, (height.toLong() * TARGET_LONG_EDGE / width).toInt())
            Pair(TARGET_LONG_EDGE, h)
        } else {
            val w = max(1, (width.toLong() * TARGET_LONG_EDGE / height).toInt())
            Pair(w, TARGET_LONG_EDGE)
        }
    }

    /**
     * Applies the chosen film preset and intensity to a standardized 2048px bitmap.
     * Multithreaded execution across CPU cores for maximum performance.
     */
    suspend fun applyFilmFilter(
        baseBitmap: Bitmap,
        preset: FilmPreset,
        intensity: Float = 1.0f
    ): Bitmap = withContext(Dispatchers.Default) {
        if (preset == FilmPreset.ORIGINAL || intensity <= 0.001f) {
            return@withContext baseBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val width = baseBitmap.width
        val height = baseBitmap.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Read source pixels into buffer
        val srcPixels = IntArray(width * height)
        baseBitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val dstPixels = IntArray(width * height)

        val clampedIntensity = intensity.coerceIn(0f, 1f)

        // For CineStill 800T: Compute the authentic optical highlight halation bloom map
        val halationMap = if (preset == FilmPreset.CINESTILL_800T) {
            computeHalationBloomMap(srcPixels, width, height)
        } else {
            null
        }

        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val chunkHeight = (height + numCores - 1) / numCores

        // Parallel processing of scanline chunks
        val deferreds = (0 until numCores).map { coreIndex ->
            async {
                val startY = coreIndex * chunkHeight
                val endY = min(height, startY + chunkHeight)
                if (startY < endY) {
                    when (preset) {
                        FilmPreset.KODACHROME_64 -> processKodachromeChunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity
                        )
                        FilmPreset.PORTRA_400 -> processPortraChunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity
                        )
                        FilmPreset.FUJI_VELVIA_50 -> processVelviaChunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity
                        )
                        FilmPreset.CINESTILL_800T -> processCineStillChunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity,
                            halationMap ?: FloatArray(0), (width + 3) / 4
                        )
                        FilmPreset.FUJI_PRO_400H -> processPro400hChunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity
                        )
                        FilmPreset.ILFORD_HP5 -> processIlfordHp5Chunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity
                        )
                        FilmPreset.KODAK_TRI_X -> processTriXChunk(
                            srcPixels, dstPixels, width, startY, endY, clampedIntensity
                        )
                        FilmPreset.ORIGINAL -> {
                            System.arraycopy(
                                srcPixels, startY * width,
                                dstPixels, startY * width,
                                (endY - startY) * width
                            )
                        }
                    }
                }
            }
        }

        deferreds.awaitAll()

        outputBitmap.setPixels(dstPixels, 0, width, 0, 0, width, height)
        return@withContext outputBitmap
    }

    /**
     * Optical Halation Bloom generator for CineStill 800T:
     * Downsamples specular highlights (L > 215) to 1/4 scale, executes a fast 2-pass
     * separable diffusion blur, and produces a normalized red halation intensity map.
     */
    private fun computeHalationBloomMap(src: IntArray, width: Int, height: Int): FloatArray {
        val dsW = (width + 3) / 4
        val dsH = (height + 3) / 4
        val rawHighlights = FloatArray(dsW * dsH)

        // Step 1: Detect specular highlights in 4x4 blocks
        for (dy in 0 until dsH) {
            val syBase = dy * 4
            for (dx in 0 until dsW) {
                val sxBase = dx * 4
                var maxHighlight = 0f
                val yLimit = min(height, syBase + 4)
                val xLimit = min(width, sxBase + 4)

                for (sy in syBase until yLimit) {
                    val row = sy * width
                    for (sx in sxBase until xLimit) {
                        val p = src[row + sx]
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF
                        val lum = (r * 299 + g * 587 + b * 114) / 1000
                        if (lum > 212) {
                            val strength = (lum - 212f) / 43f
                            if (strength > maxHighlight) {
                                maxHighlight = strength
                            }
                        }
                    }
                }
                rawHighlights[dy * dsW + dx] = maxHighlight
            }
        }

        // Step 2: 2-Pass separable 5-tap Gaussian-like diffusion blur
        val blurredH = FloatArray(dsW * dsH)
        for (y in 0 until dsH) {
            val row = y * dsW
            for (x in 0 until dsW) {
                var acc = rawHighlights[row + x] * 0.38f
                if (x > 0) acc += rawHighlights[row + x - 1] * 0.24f
                if (x < dsW - 1) acc += rawHighlights[row + x + 1] * 0.24f
                if (x > 1) acc += rawHighlights[row + x - 2] * 0.07f
                if (x < dsW - 2) acc += rawHighlights[row + x + 2] * 0.07f
                blurredH[row + x] = acc
            }
        }

        val finalMap = FloatArray(dsW * dsH)
        for (y in 0 until dsH) {
            for (x in 0 until dsW) {
                var acc = blurredH[y * dsW + x] * 0.38f
                if (y > 0) acc += blurredH[(y - 1) * dsW + x] * 0.24f
                if (y < dsH - 1) acc += blurredH[(y + 1) * dsW + x] * 0.24f
                if (y > 1) acc += blurredH[(y - 2) * dsW + x] * 0.07f
                if (y < dsH - 2) acc += blurredH[(y + 2) * dsW + x] * 0.07f
                finalMap[y * dsW + x] = acc
            }
        }

        return finalMap
    }

    // =========================================================================
    // 1. KODACHROME 64 (Reversal Slide Film, K-14 Process)
    // =========================================================================
    private fun processKodachromeChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float
    ) {
        for (y in startY until endY) {
            var rngState = (y * 31337 + 1013904223)
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
                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 25) - 12
                val dyeGrain = noiseVal * midtoneWeight * 0.48f

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
    private fun processPortraChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float
    ) {
        for (y in startY until endY) {
            var rngState = (y * 39829 + 1729481)
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
                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 19) - 9
                val tGrain = noiseVal * midtoneWeight * 0.42f

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
    private fun processVelviaChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float
    ) {
        for (y in startY until endY) {
            var rngState = (y * 53171 + 2491823)
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
                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 11) - 5
                val microGrain = noiseVal * midtoneWeight * 0.28f

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
    private fun processCineStillChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float,
        halationMap: FloatArray, dsWidth: Int
    ) {
        val hasHalation = halationMap.isNotEmpty()

        for (y in startY until endY) {
            var rngState = (y * 62723 + 3184917)
            val rowOffset = y * width
            val dy = y / 4

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

                // Authentic Optical Red Halation Bloom:
                // Photons scatter off the anti-halation pressure plate into the bottom red layer
                if (hasHalation) {
                    val dx = x / 4
                    val mapIdx = dy * dsWidth + dx
                    if (mapIdx in halationMap.indices) {
                        val bloom = halationMap[mapIdx]
                        if (bloom > 0.04f) {
                            val halationR = bloom * 88f
                            val halationG = bloom * 16f
                            val halationB = bloom * 5f
                            filmR = (filmR + halationR).coerceAtMost(255f)
                            filmG = (filmG + halationG).coerceAtMost(255f)
                            filmB = (filmB + halationB).coerceAtMost(255f)
                        }
                    }
                }

                // High-ISO 800 motion picture dye cloud grain
                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f
                val grainWeight = if (lum < 140f) {
                    (lum / 140f).pow(0.8f)
                } else {
                    ((255f - lum) / 115f).coerceAtLeast(0f)
                }

                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 35) - 17
                val cineGrain = noiseVal * grainWeight * 0.72f

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
    private fun processPro400hChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float
    ) {
        for (y in startY until endY) {
            var rngState = (y * 41143 + 1883921)
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

                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 23) - 11
                val proGrain = noiseVal * midtoneWeight * 0.44f

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
    private fun processIlfordHp5Chunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float
    ) {
        for (y in startY until endY) {
            var rngState = (y * 45293 + 1664525)
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

                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 43) - 21
                val grainDelta = (noiseVal * grainWeight * 1.35f).toInt()
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
    private fun processTriXChunk(
        src: IntArray, dst: IntArray, width: Int, startY: Int, endY: Int, intensity: Float
    ) {
        for (y in startY until endY) {
            var rngState = (y * 58211 + 2938171)
            val rowOffset = y * width
            val blockY = y shr 1

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
                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState shl 17)
                rngState = rngState xor (rngState ushr 5)

                val crystalNoise = ((rngState and 0x7FFF) % 39) - 19

                // Spatial 2x2 clumping hash for tactile metallic silver filaments (D-76 grit)
                val blockX = x shr 1
                val clumpHash = (blockX * 374761393 + blockY * 668265263) xor 0x5BF03635
                val clumpNoise = ((clumpHash and 0x7FFF) % 25) - 12

                val totalNoise = (crystalNoise * 0.65f + clumpNoise * 0.35f)
                val grainDelta = (totalNoise * grainWeight * 1.55f).toInt()
                val finalMono = (filmVal + grainDelta).coerceIn(0, 255)

                val outR = (r + (finalMono - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (finalMono - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (finalMono - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }
}
