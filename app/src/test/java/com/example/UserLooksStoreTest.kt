package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.film.CropSettings
import com.example.film.EditSettings
import com.example.film.FilmPreset
import com.example.ui.UserLooksStore
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserLooksStoreTest {
    private lateinit var context: Context
    private lateinit var file: File
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.noBackupFilesDir, "user-looks.json")
        file.delete()
        File(file.path + ".bak").delete()
    }
    @After fun cleanup() { file.delete(); File(file.path + ".bak").delete() }

    @Test fun namedRecipesRememberAdjustmentsButExcludePhotoSpecificCrop() {
        val store = UserLooksStore(context)
        val settings = EditSettings(FilmPreset.PORTRA_400, .65f, 1.1f, .4f, -.2f, .7f, CropSettings(left = .2f))
        val saved = store.saveLook("  Everyday Portra  ", settings).looks.single()
        assertEquals("Everyday Portra", saved.name)
        assertEquals(settings.copy(crop = CropSettings()), saved.settings)
        assertEquals(saved, UserLooksStore(context).load().looks.single())
        val updated = store.saveLook("Evening", settings.copy(exposure = -.5f), saved.id).looks.single()
        assertEquals(saved.id, updated.id)
        assertEquals(-.5f, updated.settings.exposure, 0f)
        assertTrue(store.deleteLook(saved.id).looks.isEmpty())
    }

    @Test fun invalidNamesLeaveThePreviousRecipesIntact() {
        val store = UserLooksStore(context)
        val original = store.saveLook("My look", EditSettings())
        for (name in listOf(" ", "x".repeat(41), "MY LOOK")) {
            assertTrue(runCatching { store.saveLook(name, EditSettings()) }.isFailure)
            assertEquals(original, UserLooksStore(context).load())
        }
        assertTrue(runCatching { store.saveLook("Changed", EditSettings(), "missing-id") }.isFailure)
        assertEquals(original, store.load())
    }

    @Test fun independentStoresMergeFavouriteChangesAndRecipes() {
        val first = UserLooksStore(context)
        val second = UserLooksStore(context)
        first.toggleFavourite(FilmPreset.PORTRA_400)
        second.saveLook("Saved", EditSettings(FilmPreset.ILFORD_HP5))
        second.toggleFavourite(FilmPreset.KODACHROME_64)
        val state = first.toggleFavourite(FilmPreset.PORTRA_400)
        assertEquals(setOf(FilmPreset.KODACHROME_64), state.favourites)
        assertEquals("Saved", state.looks.single().name)
        assertEquals(state, UserLooksStore(context).load())
    }

    @Test fun unknownPresetsAndOldRecipesHaveSafeDefaults() {
        file.writeText("""{"favourites":["FUTURE_FILM","PORTRA_400"],"looks":[{"id":"old","name":"Legacy","settings":{"preset":"REMOVED_FILM","strength":8,"grain":-3}}]}""")
        val state = UserLooksStore(context).load()
        assertEquals(setOf(FilmPreset.PORTRA_400), state.favourites)
        assertEquals(EditSettings(strength = 1f, grainIntensity = 0f), state.looks.single().settings)
    }
}
