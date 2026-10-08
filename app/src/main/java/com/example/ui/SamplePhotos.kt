package com.example.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

data class SamplePhotoItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconDescription: String
)

object SamplePhotos {

    val SAMPLES = listOf(
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

    fun generateSampleBitmap(sampleId: String): Bitmap {
        // Standard 35mm 3:2 landscape frame: 2048 x 1365
        val width = 2048
        val height = 1365
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (sampleId) {
            "street_portrait" -> renderStreetShadows(canvas, width, height, paint)
            "vintage_cafe" -> renderVintageCafe(canvas, width, height, paint)
            else -> renderCoastalDusk(canvas, width, height, paint)
        }

        return bitmap
    }

    private fun renderCoastalDusk(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        // Sky gradient: Deep twilight blue to warm golden-orange sunset
        val skyGradient = LinearGradient(
            0f, 0f, 0f, h * 0.65f,
            intArrayOf(
                Color.rgb(28, 54, 98),   // Deep indigo sky
                Color.rgb(84, 110, 150), // Steel blue
                Color.rgb(220, 125, 70), // Warm terracotta
                Color.rgb(255, 185, 95), // Golden amber horizon
                Color.rgb(255, 220, 160) // Horizon glow
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 0.90f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGradient
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.65f, paint)

        // Golden setting sun with soft atmospheric glow
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

        // Ocean water gradient
        val oceanGradient = LinearGradient(
            0f, h * 0.65f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(200, 140, 80), // Sun reflection shimmer
                Color.rgb(45, 65, 85),   // Deep coastal teal
                Color.rgb(22, 35, 48)    // Foreground deep water
            ),
            floatArrayOf(0.0f, 0.35f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = oceanGradient
        canvas.drawRect(0f, h * 0.65f, w.toFloat(), h.toFloat(), paint)

        // Sun reflection path on water
        paint.shader = LinearGradient(
            w * 0.68f, h * 0.65f, w * 0.68f, h.toFloat(),
            intArrayOf(
                Color.argb(160, 255, 210, 130),
                Color.argb(80, 255, 180, 90),
                Color.argb(10, 255, 160, 70)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        val reflectPath = Path().apply {
            moveTo(w * 0.63f, h * 0.65f)
            lineTo(w * 0.73f, h * 0.65f)
            lineTo(w * 0.82f, h.toFloat())
            lineTo(w * 0.54f, h.toFloat())
            close()
        }
        canvas.drawPath(reflectPath, paint)

        // Coastal cliffs & lighthouse silhouette
        paint.shader = null
        paint.color = Color.rgb(18, 22, 26) // Deep dark silhouette
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

        // Distant sailboat
        val boatPath = Path().apply {
            moveTo(w * 0.52f, h * 0.64f)
            lineTo(w * 0.545f, h * 0.64f)
            lineTo(w * 0.54f, h * 0.652f)
            lineTo(w * 0.525f, h * 0.652f)
            close()
        }
        canvas.drawPath(boatPath, paint)
        val sailPath = Path().apply {
            moveTo(w * 0.535f, h * 0.61f)
            lineTo(w * 0.535f, h * 0.64f)
            lineTo(w * 0.522f, h * 0.64f)
            close()
        }
        paint.color = Color.argb(200, 240, 230, 210)
        canvas.drawPath(sailPath, paint)
    }

    private fun renderStreetShadows(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        // Dramatic architectural concrete / urban light & shadow
        val bgGradient = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.rgb(215, 210, 200), // Bright sunlit wall
                Color.rgb(165, 160, 150), // Midtone concrete
                Color.rgb(75, 72, 68)     // Deep concrete shadow
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGradient
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Bold geometric architectural shadow diagonal
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

        // Shaft of high-key direct sunlight cutting across
        paint.shader = LinearGradient(
            w * 0.2f, 0f, w * 0.6f, h.toFloat(),
            intArrayOf(
                Color.argb(210, 255, 250, 240),
                Color.argb(120, 250, 235, 210),
                Color.argb(0, 255, 255, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        val beamPath = Path().apply {
            moveTo(w * 0.28f, 0f)
            lineTo(w * 0.52f, 0f)
            lineTo(w * 0.85f, h.toFloat())
            lineTo(w * 0.61f, h.toFloat())
            close()
        }
        canvas.drawPath(beamPath, paint)

        // Walking figure silhouette in the beam of light
        paint.shader = null
        paint.color = Color.rgb(15, 14, 13) // Pure silhouette black

        val figX = w * 0.56f
        val figY = h * 0.62f
        // Head
        canvas.drawCircle(figX, figY - 140f, 28f, paint)
        // Torso / trench coat
        val coatPath = Path().apply {
            moveTo(figX - 22f, figY - 110f)
            lineTo(figX + 26f, figY - 110f)
            lineTo(figX + 44f, figY + 30f)
            lineTo(figX - 35f, figY + 30f)
            close()
        }
        canvas.drawPath(coatPath, paint)
        // Legs / steps
        canvas.drawRect(figX - 28f, figY + 30f, figX - 10f, figY + 160f, paint)
        canvas.drawRect(figX + 8f, figY + 30f, figX + 26f, figY + 150f, paint)

        // Long cast shadow on the ground
        val groundShadow = Path().apply {
            moveTo(figX - 28f, figY + 160f)
            lineTo(figX + 380f, figY + 220f)
            lineTo(figX + 440f, figY + 240f)
            lineTo(figX + 10f, figY + 160f)
            close()
        }
        paint.color = Color.argb(180, 20, 19, 18)
        canvas.drawPath(groundShadow, paint)
    }

    private fun renderVintageCafe(canvas: Canvas, w: Int, h: Int, paint: Paint) {
        // Deep evening atmosphere with Kodachrome rich reds and warm tungstens
        val bgGrad = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(18, 22, 32),  // Twilight navy sky
                Color.rgb(38, 28, 34),  // Warm atmospheric haze
                Color.rgb(25, 20, 18)   // Wet evening cobblestone
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGrad
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Bistro awning with rich vintage crimson red (Kodachrome classic)
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

        // Warm interior tungsten bistro glow spill
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

        // Wet street reflection puddles
        paint.shader = LinearGradient(
            0f, h * 0.80f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(140, 220, 110, 45),
                Color.argb(60, 180, 60, 30),
                Color.argb(0, 0, 0, 0)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(w * 0.25f, h * 0.82f, w * 0.75f, h * 0.98f, paint)

        // Neon Cafe sign text / geometry
        paint.shader = null
        paint.color = Color.rgb(255, 80, 60)
        paint.strokeWidth = 14f
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(w * 0.38f, h * 0.16f, w * 0.62f, h * 0.25f, 20f, 20f, paint)
        paint.style = Paint.Style.FILL
    }
}
