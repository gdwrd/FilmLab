package com.example.film

enum class CropAspect { FREE, ORIGINAL, SQUARE, FOUR_FIVE, SIXTEEN_NINE }

/** Fractions of the oriented, quarter-turned image; straighten keeps the frame inside the source. */
data class CropSettings(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val quarterTurns: Int = 0,
    val straightenDegrees: Float = 0f,
    val aspect: CropAspect = CropAspect.ORIGINAL
) {
    fun normalized(): CropSettings {
        val l = left.takeIf(Float::isFinite)?.coerceIn(0f, .999f) ?: 0f
        val t = top.takeIf(Float::isFinite)?.coerceIn(0f, .999f) ?: 0f
        return copy(
            left = l, top = t,
            right = (right.takeIf(Float::isFinite) ?: 1f).coerceIn(l + .001f, 1f),
            bottom = (bottom.takeIf(Float::isFinite) ?: 1f).coerceIn(t + .001f, 1f),
            quarterTurns = ((quarterTurns % 4) + 4) % 4,
            straightenDegrees = (straightenDegrees.takeIf(Float::isFinite) ?: 0f).coerceIn(-10f, 10f)
        )
    }
}

data class EditSettings(
    val preset: FilmPreset = FilmPreset.ORIGINAL,
    val strength: Float = 1f,
    val grainIntensity: Float = 1f,
    val exposure: Float = 0f,
    val warmth: Float = 0f,
    val glowIntensity: Float = 1f,
    val crop: CropSettings = CropSettings()
) {
    fun normalized() = copy(
        strength = (strength.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f),
        grainIntensity = (grainIntensity.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 2f),
        exposure = (exposure.takeIf(Float::isFinite) ?: 0f).coerceIn(-2f, 2f),
        warmth = (warmth.takeIf(Float::isFinite) ?: 0f).coerceIn(-1f, 1f),
        glowIntensity = (glowIntensity.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f),
        crop = crop.normalized()
    )
}
