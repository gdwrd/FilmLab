package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.example.film.FilmPreset
import com.example.raw.RawFormat
import com.example.raw.RawMetadata
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One private working photo; an atomic pointer keeps the last edit during interrupted imports. */
internal class EditDraftStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "edit-draft")
    private val manifest = AtomicFile(File(directory, "current.json"))
    private var savedBitmap: Bitmap? = null
    private var savedPhoto: File? = null

    @Synchronized
    fun load(): FilmLabUiState? {
        if (!manifest.baseFile.exists() && !File(directory, "current.json.bak").exists()) return null
        val json = JSONObject(manifest.readFully().toString(Charsets.UTF_8))
        val name = json.getString("photo")
        require(name == File(name).name && name.startsWith("photo-") && name.endsWith(".png"))
        val file = File(directory, name)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("The saved photo is unreadable")
        savedPhoto = file
        savedBitmap = bitmap
        val preset = runCatching { FilmPreset.valueOf(json.getString("preset")) }.getOrDefault(FilmPreset.ORIGINAL)
        return FilmLabUiState(
            baseBitmap = bitmap, filmFullBitmap = bitmap,
            imageWidth = bitmap.width, imageHeight = bitmap.height,
            rawMetadata = json.optJSONObject("metadata")?.let(::readMetadata),
            selectedPreset = preset,
            intensity = json.optDouble("strength", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
            grainIntensity = json.optDouble("grain", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f,
            showWelcome = false
        )
    }

    @Synchronized
    fun save(state: FilmLabUiState, isCurrent: () -> Boolean = { true }) {
        val bitmap = state.baseBitmap ?: return
        if (!isCurrent()) return
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the edit folder" }
        val previous = savedPhoto
        val newPhoto = savedBitmap !== bitmap || previous?.exists() != true
        val photo = if (newPhoto) File(directory, "photo-" + UUID.randomUUID() + ".png") else checkNotNull(previous)
        var committed = false
        try {
            if (newPhoto) {
                FileOutputStream(photo).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not keep the current photo" }
                    stream.fd.sync()
                }
            }
            if (!isCurrent()) {
                if (newPhoto) photo.delete()
                return
            }
            val json = JSONObject()
                .put("photo", photo.name)
                .put("preset", state.selectedPreset.name)
                .put("strength", state.intensity.toDouble())
                .put("grain", state.grainIntensity.toDouble())
                .put("metadata", state.rawMetadata?.let(::writeMetadata))
            val stream = manifest.startWrite()
            try {
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                manifest.finishWrite(stream)
                committed = true
            } catch (error: Throwable) {
                manifest.failWrite(stream)
                throw error
            }
            savedBitmap = bitmap
            savedPhoto = photo
            directory.listFiles()?.filter { it.name.startsWith("photo-") && it.name.endsWith(".png") && it != photo }
                ?.forEach { it.delete() }
        } catch (error: Throwable) {
            if (newPhoto && !committed) photo.delete()
            throw error
        }
    }

    private fun writeMetadata(value: RawMetadata) = JSONObject().apply {
        put("format", value.format.name)
        put("fileName", value.fileName)
        put("make", value.make)
        put("model", value.model)
        put("lensModel", value.lensModel)
        put("focalLength", value.focalLength)
        put("aperture", value.aperture)
        put("shutterSpeed", value.shutterSpeed)
        put("iso", value.iso)
        put("exposureBias", value.exposureBias)
        put("dateTime", value.dateTime)
        put("colorSpace", value.colorSpace)
        put("originalWidth", value.originalWidth)
        put("originalHeight", value.originalHeight)
        put("orientationDegrees", value.orientationDegrees)
        put("sensorDescription", value.sensorDescription)
        put("isColorRecovered", value.isColorRecovered)
        put("processingNote", value.processingNote)
    }

    private fun readMetadata(json: JSONObject) = RawMetadata(
        format = runCatching { RawFormat.valueOf(json.getString("format")) }.getOrDefault(RawFormat.STANDARD),
        fileName = json.stringOrNull("fileName"), make = json.stringOrNull("make"), model = json.stringOrNull("model"),
        lensModel = json.stringOrNull("lensModel"), focalLength = json.stringOrNull("focalLength"),
        aperture = json.stringOrNull("aperture"), shutterSpeed = json.stringOrNull("shutterSpeed"),
        iso = json.stringOrNull("iso"), exposureBias = json.stringOrNull("exposureBias"),
        dateTime = json.stringOrNull("dateTime"), colorSpace = json.stringOrNull("colorSpace"),
        originalWidth = json.optInt("originalWidth"), originalHeight = json.optInt("originalHeight"),
        orientationDegrees = json.optInt("orientationDegrees"), sensorDescription = json.stringOrNull("sensorDescription"),
        isColorRecovered = json.optBoolean("isColorRecovered"), processingNote = json.stringOrNull("processingNote")
    )

    private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
