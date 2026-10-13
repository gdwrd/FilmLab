package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransparentPhotoTest {
    @Test fun transparentPngUsesThePhotoMatteOnceWithoutEnlargement() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.argb(128, 255, 255, 255))
        }
        val png = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)

        val normalized = FilmEngine.normalizeTo35mmResolution(decoded)

        assertEquals(2, normalized.width)
        assertEquals(1, normalized.height)
        assertEquals(Color.rgb(136, 136, 137), normalized.getPixel(0, 0))
        assertEquals(FilmEngine.PHOTO_MATTE_COLOR, normalized.getPixel(1, 0))
        assertFalse(normalized.hasAlpha())
        assertSame(normalized, FilmEngine.normalizeTo35mmResolution(normalized))
    }

    @Test fun everyFilmKeepsTheFlattenedPhotoOpaque() = runBlocking {
        val source = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 255, 255, 255))
        }
        val normalized = FilmEngine.normalizeTo35mmResolution(source)

        for (preset in FilmPreset.entries) {
            val filtered = FilmEngine.applyFilmFilter(normalized, preset)
            val pixels = IntArray(filtered.width * filtered.height)
            filtered.getPixels(pixels, 0, filtered.width, 0, 0, filtered.width, filtered.height)
            for (pixel in pixels) assertEquals(preset.name, 255, Color.alpha(pixel))
        }
    }
}
