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
    private val hp5ToneLut = IntArray(256)

    init {
        buildLookupTables()
    }

    private fun buildLookupTables() {
        // Pre-compute Kodachrome 64 tone curves:
        // C1-continuous reversal slide film S-curve with authentic K-14 positive contrast:
        // - Deep velvety blacks (high D-Max)
        // - Punchy midtone separation
        // - Smooth highlight rolloff
        for (i in 0..255) {
            val x = i / 255.0

            // Smooth continuous Hermite/Power S-curve:
            // Perfectly continuous at x = 0.5 where both branches evaluate to exactly 0.5
            val sCurve = if (x < 0.5) {
                0.5 * (2.0 * x).pow(1.26)
            } else {
                1.0 - 0.5 * (2.0 * (1.0 - x)).pow(1.26)
            }

            // RED CHANNEL:
            // High contrast with gentle midtone bell curve warmth
            val rWarm = (sCurve + 0.035 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            kodachromeRedLut[i] = (rWarm * 255.0).toInt().coerceIn(0, 255)

            // GREEN CHANNEL:
            // Balanced analog midtone response
            val gBalanced = (sCurve + 0.010 * sin(x * Math.PI)).coerceIn(0.0, 1.0)
            kodachromeGreenLut[i] = (gBalanced * 255.0).toInt().coerceIn(0, 255)

            // BLUE CHANNEL:
            // High slide film contrast curve with deep shadows and clean highlight density
            val shadowCoolLift = if (x < 0.20) 0.018 * (1.0 - x / 0.20) else 0.0
            val bCurve = (sCurve + shadowCoolLift).coerceIn(0.0, 1.0)
            kodachromeBlueLut[i] = (bCurve * 255.0).toInt().coerceIn(0, 255)

            // Ilford HP5 Plus contrast curve:
            // High micro-contrast pushed B&W negative curve with deep D-Max blacks and silvery highlights
            val hp5Curve = if (x < 0.45) {
                (x / 0.45).pow(1.4) * 0.38
            } else {
                0.38 + ((x - 0.45) / 0.55).pow(0.88) * 0.62
            }.coerceIn(0.0, 1.0)
            hp5ToneLut[i] = (hp5Curve * 255.0).toInt().coerceIn(0, 255)
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
                        FilmPreset.ILFORD_HP5 -> processIlfordHp5Chunk(
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
     * Authentic Kodachrome 64 processing:
     * - Iconic K-14 dye coupler spectral response:
     *   * Blue Sky & Cerulean tones: Deep, saturated, rich cobalt blue with red absorption
     *     (eliminating digital haze and pale purplish wash).
     *   * Warm tones: Saturated vermilion reds, amber golds, and healthy skin tones.
     *   * Neutral highlights: Subtle 5500K daylight ivory glow.
     * - Fine organic dye cloud grain (ISO 64).
     */
    private fun processKodachromeChunk(
        src: IntArray,
        dst: IntArray,
        width: Int,
        startY: Int,
        endY: Int,
        intensity: Float
    ) {
        for (y in startY until endY) {
            // Deterministic spatial RNG seed per scanline
            var rngState = (y * 31337 + 1013904223)
            val rowOffset = y * width

            for (x in 0 until width) {
                val idx = rowOffset + x
                val pixel = src[idx]

                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                // Step 1: Apply calibrated K-14 reversal S-curves
                val curveR = kodachromeRedLut[r]
                val curveG = kodachromeGreenLut[g]
                val curveB = kodachromeBlueLut[b]

                var filmR = curveR.toFloat()
                var filmG = curveG.toFloat()
                var filmB = curveB.toFloat()

                // Step 2: Authentic K-14 Tri-Pack Spectral Dye Coupling
                if (b > r) {
                    // BLUE & CERULEAN SPECTRUM (Sky, Ocean, Denim):
                    // In real Kodachrome, the cyan dye layer absorbs red light strongly.
                    // Digital sensors capture blue skies with excessive red pollution (~80-120),
                    // producing a pale or purplish digital wash.
                    // Kodachrome absorbs the red to render the iconic, deep, punchy cobalt/cerulean sky:
                    val blueDominance = (b - r).toFloat()
                    filmR = (filmR - blueDominance * 0.28f).coerceAtLeast(0f)
                    filmG = (filmG - blueDominance * 0.03f).coerceAtLeast(0f)
                    filmB = (filmB + blueDominance * 0.08f).coerceAtMost(255f)
                } else {
                    // WARM SPECTRUM (Reds, Oranges, Yellows, Sunsets, Skin):
                    // Saturated, rich, vibrant Kodak warm tones:
                    val warmDominance = (r - b).toFloat()
                    filmR = (filmR + warmDominance * 0.12f).coerceAtMost(255f)
                    filmG = (filmG + warmDominance * 0.03f).coerceAtMost(255f)
                    filmB = (filmB - warmDominance * 0.06f).coerceAtLeast(0f)
                }

                // Daylight Highlight Warmth (5500K daylight balance):
                // Applied only to bright, near-neutral highlights (e.g. sunlit clouds, white surfaces),
                // completely preserving pure saturated blue skies:
                val lum = (filmR * 299f + filmG * 587f + filmB * 114f) / 1000f
                if (lum > 195f) {
                    val neutralWeight = (1.0f - abs(r - b) / 90f).coerceAtLeast(0f)
                    val highlightWeight = ((lum - 195f) / 60f).coerceIn(0f, 1f)
                    val warmGlow = highlightWeight * neutralWeight * 7f
                    filmR = (filmR + warmGlow).coerceAtMost(255f)
                    filmG = (filmG + warmGlow * 0.45f).coerceAtMost(255f)
                    filmB = (filmB - warmGlow * 0.55f).coerceAtLeast(0f)
                }

                // Step 3: Fine organic dye cloud grain (ISO 64 is extremely fine)
                val midtoneWeight = (1.0f - abs(lum - 128f) / 128f).coerceAtLeast(0f)

                // High-speed XorShift random step
                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                val noiseVal = ((rngState and 0x7FFF) % 25) - 12 // Range [-12, +12]
                val dyeGrain = noiseVal * midtoneWeight * 0.48f

                // Uniform luminance modulation avoids digital chromatic noise
                filmR = (filmR + dyeGrain).coerceIn(0f, 255f)
                filmG = (filmG + dyeGrain).coerceIn(0f, 255f)
                filmB = (filmB + dyeGrain).coerceIn(0f, 255f)

                // Step 4: Blend with original based on intensity
                val outR = (r + (filmR - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (filmG - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (filmB - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    /**
     * Ilford HP5 Plus 400 processing:
     * - Panchromatic spectral sensitivity (simulating yellow contrast filter #8).
     * - High-contrast pushed S-curve with deep D-Max blacks and silvery highlights.
     * - Tactile silver halide crystalline grain structure.
     */
    private fun processIlfordHp5Chunk(
        src: IntArray,
        dst: IntArray,
        width: Int,
        startY: Int,
        endY: Int,
        intensity: Float
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
                // Lifted red and green separates skies and enriches skin tones
                val monoLum = ((r * 328 + g * 553 + b * 143) shr 10).coerceIn(0, 255)

                // Apply HP5 pushed contrast curve
                val filmVal = hp5ToneLut[monoLum]

                // Silver halide grain physics:
                // Tactile crystalline clumps prominent in zones IV through VII (midtones and highlights)
                val grainWeight = if (filmVal < 120) {
                    (filmVal / 120f).pow(1.1f)
                } else {
                    ((255 - filmVal) / 135f).coerceAtLeast(0f).pow(0.85f)
                }.coerceIn(0f, 1f)

                // High-speed XorShift random step for tactile silver grain
                rngState = rngState xor (rngState shl 13)
                rngState = rngState xor (rngState ushr 17)
                rngState = rngState xor (rngState shl 5)

                // HP5 has classic coarser 400-ISO tooth
                val noiseVal = ((rngState and 0x7FFF) % 43) - 21 // Range [-21, +21]
                val grainDelta = (noiseVal * grainWeight * 1.35f).toInt()

                val finalMono = (filmVal + grainDelta).coerceIn(0, 255)

                // Blend with original color pixel according to intensity slider
                val outR = (r + (finalMono - r) * intensity).toInt().coerceIn(0, 255)
                val outG = (g + (finalMono - g) * intensity).toInt().coerceIn(0, 255)
                val outB = (b + (finalMono - b) * intensity).toInt().coerceIn(0, 255)

                dst[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }
}
