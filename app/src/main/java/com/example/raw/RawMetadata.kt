package com.example.raw

/**
 * Encapsulates camera shooting metadata and RAW sensor details.
 */
data class RawMetadata(
    val format: RawFormat = RawFormat.STANDARD,
    val fileName: String? = null,
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: String? = null,
    val exposureBias: String? = null,
    val dateTime: String? = null,
    val colorSpace: String? = null,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val orientationDegrees: Int = 0,
    val sensorDescription: String? = null,
    val isColorRecovered: Boolean = false
) {
    /**
     * Resolves human-friendly camera name, converting Sony internal model codes
     * (e.g. ILCE-7M3 -> Sony α7 III) and Leica model codes into recognizable camera names.
     */
    val displayCameraName: String
        get() {
            val rawModel = model?.trim() ?: return make ?: if (format.isRaw) format.formatName else "Standard 35mm"
            val upperModel = rawModel.uppercase()

            return when {
                // Sony Alpha Models
                upperModel.contains("ILCE-7M4") || upperModel.contains("A7 IV") || upperModel.contains("A7M4") -> "Sony α7 IV"
                upperModel.contains("ILCE-7M3") || upperModel.contains("A7 III") || upperModel.contains("A7M3") -> "Sony α7 III"
                upperModel.contains("ILCE-7M2") || upperModel.contains("A7 II") || upperModel.contains("A7M2") -> "Sony α7 II"
                upperModel.contains("ILCE-7RM5") || upperModel.contains("A7R V") || upperModel.contains("A7RM5") -> "Sony α7R V"
                upperModel.contains("ILCE-7RM4") || upperModel.contains("A7R IV") || upperModel.contains("A7RM4") -> "Sony α7R IV"
                upperModel.contains("ILCE-7RM3") || upperModel.contains("A7R III") || upperModel.contains("A7RM3") -> "Sony α7R III"
                upperModel.contains("ILCE-7RM2") || upperModel.contains("A7R II") || upperModel.contains("A7RM2") -> "Sony α7R II"
                upperModel.contains("ILCE-7R") -> "Sony α7R"
                upperModel.contains("ILCE-7SM3") || upperModel.contains("A7S III") || upperModel.contains("A7SM3") -> "Sony α7S III"
                upperModel.contains("ILCE-7SM2") || upperModel.contains("A7S II") || upperModel.contains("A7SM2") -> "Sony α7S II"
                upperModel.contains("ILCE-7S") -> "Sony α7S"
                upperModel.contains("ILCE-7CR") || upperModel.contains("A7CR") -> "Sony α7CR"
                upperModel.contains("ILCE-7CM2") || upperModel.contains("A7C II") -> "Sony α7C II"
                upperModel.contains("ILCE-7C") || upperModel.contains("A7C") -> "Sony α7C"
                upperModel.contains("ILCE-7") || upperModel == "A7" -> "Sony α7"
                upperModel.contains("ILCE-9") || upperModel.contains("A9") -> "Sony α9"
                upperModel.contains("ILCE-1") || upperModel.contains("A1") -> "Sony α1"

                // Leica Camera Models
                upperModel.contains("LEICA Q3") || upperModel == "Q3" -> "Leica Q3"
                upperModel.contains("LEICA Q2") || upperModel == "Q2" -> "Leica Q2"
                upperModel.contains("LEICA Q") || upperModel == "Q (TYP 116)" -> "Leica Q"
                upperModel.contains("LEICA M11") -> "Leica M11"
                upperModel.contains("LEICA M10") -> "Leica M10"
                upperModel.contains("LEICA M (TYP 240)") || upperModel == "M240" -> "Leica M (Typ 240)"
                upperModel.contains("LEICA M9") -> "Leica M9"
                upperModel.contains("LEICA SL3") -> "Leica SL3"
                upperModel.contains("LEICA SL2") -> "Leica SL2"
                upperModel.contains("LEICA SL") -> "Leica SL"
                upperModel.contains("D-LUX 7") || upperModel.contains("D-LUX7") -> "Leica D-Lux 7"
                upperModel.contains("D-LUX 8") || upperModel.contains("D-LUX8") -> "Leica D-Lux 8"

                make != null && !rawModel.startsWith(make, ignoreCase = true) -> "$make $rawModel"
                else -> rawModel
            }
        }

    val isLeica: Boolean
        get() = make?.contains("Leica", ignoreCase = true) == true ||
                model?.contains("Leica", ignoreCase = true) == true

    val resolvedBadgeLabel: String
        get() = when {
            isLeica -> "RAW • LEICA DNG"
            format == RawFormat.SONY_ARW -> "RAW • SONY ARW"
            format == RawFormat.DNG -> "RAW • ADOBE DNG"
            else -> "35mm SCAN"
        }

    /**
     * Compact telemetry string for HUD display (e.g. "35mm • f/1.4 • 1/500s • ISO 100").
     */
    val hudTelemetryLine: String
        get() {
            val parts = mutableListOf<String>()
            lensModel?.let { parts.add(it) } ?: focalLength?.let { parts.add(it) }
            aperture?.let { parts.add(it) }
            shutterSpeed?.let { parts.add(it) }
            iso?.let { parts.add(it) }
            return if (parts.isNotEmpty()) parts.joinToString(" · ") else "Full-Frame 35mm Raw Capture"
        }

    /**
     * Aspect ratio calculation.
     */
    val aspectRatioLabel: String
        get() {
            if (originalWidth <= 0 || originalHeight <= 0) return "3:2"
            val ratio = originalWidth.toFloat() / originalHeight.toFloat()
            return when {
                ratio in 1.45f..1.55f || ratio in 0.63f..0.69f -> "3:2 (35mm)"
                ratio in 1.30f..1.38f || ratio in 0.72f..0.77f -> "4:3"
                ratio in 1.70f..1.85f || ratio in 0.54f..0.59f -> "16:9"
                ratio in 0.95f..1.05f -> "1:1"
                else -> String.format(java.util.Locale.US, "%.2f:1", ratio)
            }
        }
}
