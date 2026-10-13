package com.example.ui

import android.content.Context
import android.util.AtomicFile
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.EditSettings
import com.example.film.FilmPreset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UserLooksState(val favourites: Set<FilmPreset> = emptySet(), val looks: List<SavedLook> = emptyList())

internal class UserLooksStore(context: Context) {
    private companion object { val lock = Any() }
    private val manifest = AtomicFile(File(context.noBackupFilesDir, "user-looks.json"))

    fun load(): UserLooksState = synchronized(lock) { read() }

    fun toggleFavourite(preset: FilmPreset): UserLooksState = synchronized(lock) {
        val state = read()
        write(state.copy(favourites = if (preset in state.favourites) state.favourites - preset else state.favourites + preset))
    }

    fun saveLook(name: String, settings: EditSettings, id: String? = null): UserLooksState = synchronized(lock) {
        val state = read()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Give your look a name." }
        require(trimmed.length <= 40) { "Use a name with 40 characters or fewer." }
        require(state.looks.none { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) { "A look already has that name." }
        require(id == null || state.looks.any { it.id == id }) { "This saved look no longer exists." }
        require(id != null || state.looks.size < 50) { "You have 50 saved looks. Delete one before adding another." }
        val look = SavedLook(id ?: UUID.randomUUID().toString(), trimmed, settings.normalized().copy(crop = CropSettings()))
        write(state.copy(looks = if (id == null) state.looks + look else state.looks.map { if (it.id == id) look else it }))
    }

    fun deleteLook(id: String): UserLooksState = synchronized(lock) {
        val state = read()
        write(state.copy(looks = state.looks.filterNot { it.id == id }))
    }

    private fun read(): UserLooksState {
        if (!manifest.baseFile.exists() && !File(manifest.baseFile.path + ".bak").exists()) return UserLooksState()
        val json = JSONObject(manifest.readFully().toString(Charsets.UTF_8))
        val favourites = json.optJSONArray("favourites") ?: JSONArray()
        val looks = json.optJSONArray("looks") ?: JSONArray()
        return UserLooksState(
            (0 until favourites.length()).mapNotNull { index ->
                runCatching { FilmPreset.valueOf(favourites.getString(index)) }.getOrNull()
            }.toSet(),
            (0 until looks.length()).mapNotNull { index ->
                val value = looks.optJSONObject(index) ?: return@mapNotNull null
                val id = value.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = value.optString("name").trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: return@mapNotNull null
                SavedLook(id, name, (value.optJSONObject("settings") ?: JSONObject()).readEditSettings().copy(crop = CropSettings()))
            }.distinctBy { it.id }
        )
    }

    private fun write(state: UserLooksState): UserLooksState {
        check(manifest.baseFile.parentFile!!.isDirectory || manifest.baseFile.parentFile!!.mkdirs()) { "Could not keep your saved looks." }
        val json = JSONObject().put("version", 1)
            .put("favourites", JSONArray(state.favourites.map { it.name }))
            .put("looks", JSONArray(state.looks.map { JSONObject().put("id", it.id).put("name", it.name).put("settings", it.settings.writeEditSettings()) }))
        val stream = manifest.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            manifest.finishWrite(stream)
        } catch (error: Throwable) {
            manifest.failWrite(stream)
            throw error
        }
        return state
    }
}

internal fun EditSettings.writeEditSettings() = normalized().let { settings ->
    JSONObject().put("preset", settings.preset.name).put("strength", settings.strength.toDouble())
        .put("grain", settings.grainIntensity.toDouble()).put("exposure", settings.exposure.toDouble())
        .put("warmth", settings.warmth.toDouble()).put("glow", settings.glowIntensity.toDouble())
        .put("crop", JSONObject().put("left", settings.crop.left.toDouble()).put("top", settings.crop.top.toDouble())
            .put("right", settings.crop.right.toDouble()).put("bottom", settings.crop.bottom.toDouble())
            .put("quarterTurns", settings.crop.quarterTurns).put("straighten", settings.crop.straightenDegrees.toDouble())
            .put("aspect", settings.crop.aspect.name))
}

internal fun JSONObject.readEditSettings(): EditSettings {
    val crop = optJSONObject("crop") ?: JSONObject()
    return EditSettings(
        preset = runCatching { FilmPreset.valueOf(optString("preset")) }.getOrDefault(FilmPreset.ORIGINAL),
        strength = optDouble("strength", 1.0).toFloat(), grainIntensity = optDouble("grain", 1.0).toFloat(),
        exposure = optDouble("exposure", 0.0).toFloat(), warmth = optDouble("warmth", 0.0).toFloat(),
        glowIntensity = optDouble("glow", 1.0).toFloat(),
        crop = CropSettings(crop.optDouble("left", 0.0).toFloat(), crop.optDouble("top", 0.0).toFloat(),
            crop.optDouble("right", 1.0).toFloat(), crop.optDouble("bottom", 1.0).toFloat(), crop.optInt("quarterTurns"),
            crop.optDouble("straighten", 0.0).toFloat(),
            runCatching { CropAspect.valueOf(crop.optString("aspect")) }.getOrDefault(CropAspect.ORIGINAL))
    ).normalized()
}
