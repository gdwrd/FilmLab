package com.example.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.example.raw.RawFormat
import com.example.raw.RawMetadata

data class SamplePhotoItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconDescription: String,
    val badgeLabel: String? = null
)

object SamplePhotos {

    val SAMPLES = listOf(
        SamplePhotoItem(
            id = "raw_sony_a7m3",
            title = "Sony α7 III RAW",
            subtitle = "Shibuya Night • FE 35mm F1.4",
            iconDescription = "Sony Alpha 7 camera RAW test scene",
            badgeLabel = "RAW • SONY ARW"
        ),
        SamplePhotoItem(
            id = "raw_adobe_dng",
            title = "Adobe DNG RAW",
            subtitle = "High Dynamic Range Sunset • 14-bit",
            iconDescription = "Adobe Digital Negative RAW test scene",
            badgeLabel = "RAW • DNG"
        ),
        SamplePhotoItem(
            id = "golden_coastal",
            title = "Coastal Dusk",
            subtitle = "Golden hour skyline & ocean",
            iconDescription = "Sun and horizon"
        ),
        SamplePhotoItem(
            id = "street_portrait",
            title = "Street Shadows",
            subtitle = "High-contrast urban geometry",
            iconDescription = "Urban architectural silhouettes"
        ),
        SamplePhotoItem(
            id = "vintage_cafe",
            title = "Vintage Cafe",
            subtitle = "Rich reds, neon & warm evening",
            iconDescription = "Analog cafe neon scene"
        )
    )

    fun getSampleMetadata(sampleId: String): RawMetadata? {
        return when (sampleId) {
            "raw_sony_a7m3" -> RawMetadata(
                format = RawFormat.SONY_ARW,
                fileName = "DSC08492.ARW",
                make = "SONY",
                model = "ILCE-7M3",
                lensModel = "FE 35mm F1.4 GM",
                focalLength = "35mm",
                aperture = "f/1.4",
                shutterSpeed = "1/250s",
                iso = "ISO 400",
                exposureBias = "±0.0 EV",
                dateTime = "2024:10:14 19:42:10",
                colorSpace = "Camera Native RAW Gamut (Sony S-Gamut3)",
                originalWidth = 6000,
                originalHeight = 4000,
                sensorDescription = "35mm Full-Frame 24.2MP BSI Exmor R CMOS • 14-bit ARW"
            )
            "raw_adobe_dng" -> RawMetadata(
                format = RawFormat.DNG,
                fileName = "IMG_3591.DNG",
                make = "Leica Camera AG",
                model = "Leica M10-R",
                lensModel = "Summilux-M 35mm f/1.4 ASPH.",
                focalLength = "35mm",
                aperture = "f/2.0",
                shutterSpeed = "1/1000s",
                iso = "ISO 100",
                exposureBias = "-0.3 EV",
                dateTime = "2024:09:22 17:15:08",
                colorSpace = "Adobe RGB (1998)",
                originalWidth = 7864,
                originalHeight = 5200,
                sensorDescription = "Adobe Digital Negative (DNG 1.6) • 14-bit Lossless Bayer CFA"
            )
            "raw_leica_bw_color" -> RawMetadata(
                format = RawFormat.DNG,
                fileName = "L1008421.DNG",
                make = "Leica Camera AG",
                model = "Leica Q2",
                lensModel = "Summilux 28mm f/1.7 ASPH.",
                focalLength = "28mm",
                aperture = "f/1.7",
                shutterSpeed = "1/500s",
                iso = "ISO 200",
                exposureBias = "±0.0 EV",
                dateTime = "2024:08:18 16:30:00",
                colorSpace = "Restored Full Sensor Color (B&W Bypassed)",
                originalWidth = 8368,
                originalHeight = 5584,
                isColorRecovered = true,
                sensorDescription = "Leica 47.3MP Maestro II CFA • Original Sensor Color Restored"
            )
            "golden_coastal" -> RawMetadata(
                format = RawFormat.STANDARD,
                fileName = "Coastal_Dusk_Scan.jpg",
                make = "Fujifilm",
                model = "Frontier SP-3000 Scanner",
                focalLength = "50mm",
                aperture = "f/4.0",
                shutterSpeed = "1/125s",
                iso = "ISO 100",
                originalWidth = 2048,
                originalHeight = 1365
            )
            "street_portrait" -> RawMetadata(
                format = RawFormat.STANDARD,
                fileName = "Street_Shadows_35mm.jpg",
                make = "Leica",
                model = "Leica M6",
                lensModel = "Summicron 50mm f/2",
                focalLength = "50mm",
                aperture = "f/2.8",
                shutterSpeed = "1/500s",
                iso = "ISO 400",
                originalWidth = 2048,
                originalHeight = 1365
            )
            "vintage_cafe" -> RawMetadata(
                format = RawFormat.STANDARD,
                fileName = "Vintage_Cafe_K64.jpg",
                make = "Nikon",
                model = "Nikon F3",
                lensModel = "Nikkor 35mm f/1.4",
                focalLength = "35mm",
                aperture = "f/1.4",
                shutterSpeed = "1/60s",
                iso = "ISO 64",
                originalWidth = 2048,
                originalHeight = 1365
            )
            else -> null
        }
    }

    fun generateSampleBitmap(sampleId: String): Bitmap {
        // Standard 35mm 3:2 landscape frame: 2048 x 1365
        val width = 2048
        val height = 1365
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (sampleId) {
            "raw_sony_a7m3" -> renderSonyA7RawScene(canvas, width, height, paint)
            "raw_adobe_dng" -> renderAdobeDngScene(canvas, width, height, paint)
            "street_portrait" -> renderStreetShadows(canvas, width, height, paint)
            "vintage_cafe" -> renderVintageCafe(canvas, width, height, paint)
            else -> renderCoastalDusk(canvas, width, height, paint)
        }

        return bitmap
    }

    private fun renderSonyA7RawScene(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        // Sony Alpha high-dynamic-range night street atmosphere (Shibuya Tokyo night)
        val skyGrad = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(12, 16, 26),  // Deep midnight blue/indigo
                Color.rgb(24, 20, 36),  // Atmospheric magenta/purple night haze
                Color.rgb(18, 14, 18)   // Wet asphalt reflection
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGrad
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Building geometry silhouettes
        paint.shader = null
        paint.color = Color.rgb(15, 18, 24)
        canvas.drawRect(0f, h * 0.15f, w * 0.28f, h * 0.82f, paint)
        canvas.drawRect(w * 0.72f, h * 0.08f, w.toFloat(), h * 0.82f, paint)

        // Mid-distance buildings
        paint.color = Color.rgb(20, 24, 32)
        canvas.drawRect(w * 0.26f, h * 0.25f, w * 0.45f, h * 0.80f, paint)
        canvas.drawRect(w * 0.55f, h * 0.20f, w * 0.74f, h * 0.80f, paint)

        // Wet asphalt street reflections (Full Frame 14-bit dynamic range)
        val wetStreetGrad = LinearGradient(
            0f, h * 0.78f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(80, 40, 180, 240),  // Cyan neon reflection
                Color.argb(140, 255, 60, 140), // Magenta billboard spill
                Color.argb(60, 255, 190, 60),  // Amber street light puddle
                Color.argb(255, 14, 12, 16)    // Wet black pavement
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = wetStreetGrad
        canvas.drawRect(0f, h * 0.78f, w.toFloat(), h.toFloat(), paint)

        // Cyberpunk / Shibuya neon signs
        paint.shader = null
        // Cyan neon sign
        paint.color = Color.rgb(40, 210, 255)
        paint.strokeWidth = 10f
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(w * 0.12f, h * 0.28f, w * 0.22f, h * 0.44f, 16f, 16f, paint)

        // Magenta vertical neon sign
        paint.color = Color.rgb(255, 45, 135)
        canvas.drawRoundRect(w * 0.76f, h * 0.18f, w * 0.82f, h * 0.50f, 12f, 12f, paint)

        // Golden warm ramen shop glow
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            w * 0.48f, h * 0.65f, 280f,
            intArrayOf(
                Color.argb(220, 255, 195, 80),
                Color.argb(90, 255, 120, 30),
                Color.argb(0, 200, 50, 20)
            ),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.48f, h * 0.65f, 280f, paint)

        // Bokeh discs from Sony FE 35mm F1.4 GM aperture
        paint.shader = null
        val bokehColors = intArrayOf(
            Color.argb(130, 255, 80, 160),
            Color.argb(140, 50, 220, 255),
            Color.argb(150, 255, 215, 90),
            Color.argb(110, 255, 140, 50),
            Color.argb(120, 120, 240, 180)
        )
        val bokehCoords = arrayOf(
            floatArrayOf(w * 0.35f, h * 0.48f, 54f),
            floatArrayOf(w * 0.38f, h * 0.52f, 38f),
            floatArrayOf(w * 0.62f, h * 0.42f, 62f),
            floatArrayOf(w * 0.67f, h * 0.46f, 44f),
            floatArrayOf(w * 0.52f, h * 0.38f, 32f),
            floatArrayOf(w * 0.85f, h * 0.68f, 75f),
            floatArrayOf(w * 0.15f, h * 0.65f, 68f)
        )
        for (i in bokehCoords.indices) {
            paint.color = bokehColors[i % bokehColors.size]
            canvas.drawCircle(bokehCoords[i][0], bokehCoords[i][1], bokehCoords[i][2], paint)
        }

        // Night street pedestrian silhouette with umbrella
        paint.color = Color.rgb(10, 8, 12)
        val pedX = w * 0.50f
        val pedY = h * 0.74f
        // Umbrella
        val umbrellaPath = Path().apply {
            moveTo(pedX - 70f, pedY - 60f)
            quadTo(pedX, pedY - 110f, pedX + 70f, pedY - 60f)
            close()
        }
        canvas.drawPath(umbrellaPath, paint)
        // Body silhouette
        canvas.drawRect(pedX - 25f, pedY - 60f, pedX + 25f, pedY + 80f, paint)
    }

    private fun renderAdobeDngScene(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        // High Dynamic Range DNG landscape: Mountain range at twilight with intense sunset glow
        val skyGrad = LinearGradient(
            0f, 0f, 0f, h * 0.70f,
            intArrayOf(
                Color.rgb(18, 42, 85),   // High altitude deep cyan/indigo
                Color.rgb(65, 88, 130),  // Steel alpine blue
                Color.rgb(205, 95, 75),  // Terracotta alpenglow
                Color.rgb(255, 160, 65), // Golden horizon
                Color.rgb(255, 230, 160) // High-key solar highlight
            ),
            floatArrayOf(0.0f, 0.30f, 0.65f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGrad
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.70f, paint)

        // Intense solar disc with 14-bit DNG highlight transition
        paint.shader = RadialGradient(
            w * 0.72f, h * 0.52f, 380f,
            intArrayOf(
                Color.argb(255, 255, 250, 230),
                Color.argb(160, 255, 180, 80),
                Color.argb(0, 240, 100, 40)
            ),
            floatArrayOf(0.0f, 0.35f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.72f, h * 0.52f, 380f, paint)

        // Distant alpine mountain peaks with atmospheric haze
        paint.shader = null
        paint.color = Color.rgb(75, 45, 65)
        val peak1 = Path().apply {
            moveTo(w * 0.10f, h * 0.68f)
            lineTo(w * 0.32f, h * 0.44f)
            lineTo(w * 0.48f, h * 0.68f)
            close()
        }
        canvas.drawPath(peak1, paint)

        paint.color = Color.rgb(55, 35, 52)
        val peak2 = Path().apply {
            moveTo(w * 0.42f, h * 0.68f)
            lineTo(w * 0.62f, h * 0.38f)
            lineTo(w * 0.84f, h * 0.68f)
            close()
        }
        canvas.drawPath(peak2, paint)

        // Alpine lake foreground reflection
        val lakeGrad = LinearGradient(
            0f, h * 0.68f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(180, 110, 65), // Sunset shimmer reflection
                Color.rgb(36, 50, 68),   // Deep alpine water
                Color.rgb(16, 24, 34)    // Foreground shoreline
            ),
            floatArrayOf(0.0f, 0.40f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = lakeGrad
        canvas.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), paint)

        // Foreground pine trees silhouette
        paint.shader = null
        paint.color = Color.rgb(14, 18, 22)
        val treesPath = Path().apply {
            moveTo(0f, h * 0.85f)
            lineTo(w * 0.08f, h * 0.58f)
            lineTo(w * 0.16f, h * 0.88f)
            lineTo(w * 0.22f, h * 0.62f)
            lineTo(w * 0.28f, h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }
        canvas.drawPath(treesPath, paint)
    }

    private fun renderCoastalDusk(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        val skyGradient = LinearGradient(
            0f, 0f, 0f, h * 0.65f,
            intArrayOf(
                Color.rgb(28, 54, 98),
                Color.rgb(84, 110, 150),
                Color.rgb(220, 125, 70),
                Color.rgb(255, 185, 95),
                Color.rgb(255, 220, 160)
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 0.90f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGradient
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.65f, paint)

        paint.shader = RadialGradient(
            w * 0.68f, h * 0.55f, 320f,
            intArrayOf(
                Color.argb(240, 255, 245, 210),
                Color.argb(140, 255, 175, 60),
                Color.argb(0, 255, 130, 40)
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.68f, h * 0.55f, 320f, paint)

        val oceanGradient = LinearGradient(
            0f, h * 0.65f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(200, 140, 80),
                Color.rgb(45, 65, 85),
                Color.rgb(22, 35, 48)
            ),
            floatArrayOf(0.0f, 0.35f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = oceanGradient
        canvas.drawRect(0f, h * 0.65f, w.toFloat(), h.toFloat(), paint)

        paint.shader = null
        paint.color = Color.rgb(18, 22, 26)
        val cliffPath = Path().apply {
            moveTo(0f, h * 0.62f)
            lineTo(w * 0.28f, h * 0.66f)
            lineTo(w * 0.36f, h * 0.74f)
            lineTo(w * 0.45f, h * 0.82f)
            lineTo(w * 0.40f, h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }
        canvas.drawPath(cliffPath, paint)
    }

    private fun renderStreetShadows(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        val bgGradient = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.rgb(215, 210, 200),
                Color.rgb(165, 160, 150),
                Color.rgb(75, 72, 68)
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGradient
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        paint.shader = null
        paint.color = Color.rgb(24, 23, 22)
        val shadowPath = Path().apply {
            moveTo(w * 0.15f, 0f)
            lineTo(w.toFloat(), 0f)
            lineTo(w.toFloat(), h * 0.88f)
            lineTo(w * 0.42f, h.toFloat())
            lineTo(0f, h.toFloat())
            lineTo(0f, h * 0.35f)
            close()
        }
        canvas.drawPath(shadowPath, paint)
    }

    private fun renderVintageCafe(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        val bgGrad = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(18, 22, 32),
                Color.rgb(38, 28, 34),
                Color.rgb(25, 20, 18)
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGrad
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        paint.shader = null
        paint.color = Color.rgb(180, 32, 28)
        val awningPath = Path().apply {
            moveTo(w * 0.12f, h * 0.28f)
            lineTo(w * 0.88f, h * 0.28f)
            lineTo(w * 0.94f, h * 0.46f)
            lineTo(w * 0.06f, h * 0.46f)
            close()
        }
        canvas.drawPath(awningPath, paint)

        paint.shader = RadialGradient(
            w * 0.50f, h * 0.60f, 550f,
            intArrayOf(
                Color.argb(220, 255, 195, 80),
                Color.argb(120, 220, 120, 40),
                Color.argb(0, 180, 70, 20)
            ),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(w * 0.14f, h * 0.46f, w * 0.86f, h * 0.82f, paint)
    }
}
