package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.example.raw.RawFormat
import com.example.raw.RawMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/** Private recent edits; publish all asset references before removing any previous files. */
internal class EditDraftStore(context: Context) {
    private companion object {
        // A closing editor's IO can overlap the next editor instance.
        val lock = Any()
        const val MAX_EDITS = 20
        const val MAX_BYTES = 512L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 256L * 1024 * 1024
    }

    private val directory = File(context.noBackupFilesDir, "edit-draft")
    private val manifest = AtomicFile(File(directory, "current.json"))
    private data class CachedPhoto(val bitmap: Bitmap, val photo: File, val sourceInput: File?, val source: File?)
    private var cachedPhotoId: String? = null
    private var cachedPhoto: CachedPhoto? = null
    private val knownPhotoIds = mutableSetOf<String>()

    fun load(id: String? = null): FilmLabUiState? = synchronized(lock) {
        val document = readDocument() ?: return@synchronized null
        val json = entries(document).firstOrNull { it.getString("id") == (id ?: document.stringOrNull("current")) }
            ?: return@synchronized null
        val photoId = json.getString("id")
        val file = asset(json.getString("photo"), "photo-")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("The saved photo is unreadable")
        val source = json.stringOrNull("source")?.let { asset(it, "source-") }?.takeIf(File::isFile)
        cachedPhotoId = photoId
        cachedPhoto = CachedPhoto(bitmap, file, source, source)
        knownPhotoIds += photoId
        val settings = json.readEditSettings()
        FilmLabUiState(
            baseBitmap = bitmap, filmFullBitmap = bitmap,
            photoId = photoId, sourceFile = source,
            sourceWidth = json.optInt("sourceWidth").takeIf { it > 0 } ?: bitmap.width,
            sourceHeight = json.optInt("sourceHeight").takeIf { it > 0 } ?: bitmap.height,
            imageWidth = bitmap.width, imageHeight = bitmap.height,
            rawMetadata = json.optJSONObject("metadata")?.let(::readMetadata),
            selectedPreset = settings.preset, intensity = settings.strength, grainIntensity = settings.grainIntensity,
            exposure = settings.exposure, warmth = settings.warmth, glowIntensity = settings.glowIntensity, crop = settings.crop,
            showWelcome = false
        )
    }

    fun save(state: FilmLabUiState, isCurrent: () -> Boolean = { true }): Unit = synchronized(lock) {
        val bitmap = state.baseBitmap ?: return@synchronized
        if (!isCurrent()) return@synchronized
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the edit folder" }
        val document = readDocument() ?: JSONObject().put("version", 2).put("edits", JSONArray())
        val records = entries(document)
        val id = state.photoId ?: document.stringOrNull("current") ?: UUID.randomUUID().toString()
        if (document.optJSONObject("removed")?.optString("id") == id) return@synchronized
        val previous = records.firstOrNull { it.getString("id") == id }
        // A late save from an editor closed before deletion must not resurrect its photo.
        if (previous == null && id in knownPhotoIds) return@synchronized
        val removedCount = if (document.optJSONObject("removed") == null) 0 else 1
        check(previous != null || records.size + removedCount < MAX_EDITS) { "You have 20 recent edits. Delete an old edit before keeping another." }
        val cached = cachedPhoto.takeIf { cachedPhotoId == id }
        val oldPhoto = previous?.stringOrNull("photo")?.let { asset(it, "photo-") }
        val newPhoto = cached?.bitmap !== bitmap || cached?.photo != oldPhoto || oldPhoto?.isFile != true
        val photo = if (newPhoto) File(directory, "photo-${UUID.randomUUID()}.png") else checkNotNull(oldPhoto)
        val oldSource = previous?.stringOrNull("source")?.let { asset(it, "source-") }
        val inputSource = state.sourceFile
        val reuseSource = inputSource == null || inputSource == oldSource || (cached?.sourceInput == inputSource && cached?.source == oldSource && oldSource?.isFile == true)
        val source = if (reuseSource) oldSource else File(directory, "source-${UUID.randomUUID()}.${inputSource!!.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "img"}")
        val newSource = !reuseSource
        var committed = false
        try {
            if (newPhoto) {
                FileOutputStream(photo).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not keep the current photo" }
                    stream.fd.sync()
                }
            }
            if (newSource) {
                check(inputSource!!.isFile) { "The original photo is no longer available. Reopen it to keep the largest size." }
                check(inputSource.length() <= MAX_SOURCE_BYTES) { "This original is larger than 256 MB. Choose a smaller photo." }
                inputSource.inputStream().use { input ->
                    FileOutputStream(checkNotNull(source)).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            if (!isCurrent()) return@synchronized
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= MAX_SOURCE_BYTES) { "This original is larger than 256 MB. Choose a smaller photo." }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            if (!isCurrent()) return@synchronized
            val json = state.editSettings.writeEditSettings()
                .put("id", id).put("updatedAt", System.currentTimeMillis())
                .put("photo", photo.name)
                .put("source", source?.name)
                .put("sourceWidth", state.sourceWidth.takeIf { it > 0 } ?: previous?.optInt("sourceWidth")?.takeIf { it > 0 } ?: bitmap.width)
                .put("sourceHeight", state.sourceHeight.takeIf { it > 0 } ?: previous?.optInt("sourceHeight")?.takeIf { it > 0 } ?: bitmap.height)
                .put("metadata", state.rawMetadata?.let(::writeMetadata))
            document.put("edits", JSONArray(records.filterNot { it.getString("id") == id } + json)).put("current", id)
            check(referencedAssets(document).sumOf { it.length() } <= MAX_BYTES) { "Your recent edits use 512 MB. Delete an old edit before keeping another." }
            committed = publish(document, isCurrent)
            if (!committed) return@synchronized
            cachedPhotoId = id
            cachedPhoto = CachedPhoto(bitmap, photo, inputSource, source)
            knownPhotoIds += id
            cleanup(document)
        } finally {
            if (newPhoto && !committed) photo.delete()
            if (newSource && !committed) source?.delete()
        }
    }

    fun list(): List<RecentEdit> = synchronized(lock) {
        val document = readDocument() ?: return@synchronized emptyList()
        entries(document).sortedByDescending { it.optLong("updatedAt") }.map { json ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val file = asset(json.getString("photo"), "photo-")
            BitmapFactory.decodeFile(file.path, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = max(1, max(options.outWidth, options.outHeight) / 160)
            RecentEdit(json.getString("id"), title(json), json.readEditSettings().preset,
                BitmapFactory.decodeFile(file.path, options), json.optLong("updatedAt"))
        }
    }

    fun sourceFileFor(id: String?): File? = synchronized(lock) {
        val document = readDocument() ?: return@synchronized null
        entries(document).firstOrNull { it.getString("id") == (id ?: document.stringOrNull("current")) }
            ?.stringOrNull("source")?.let { asset(it, "source-") }?.takeIf(File::isFile)
    }

    fun remove(id: String): String? = synchronized(lock) {
        val document = readDocument() ?: return@synchronized null
        val records = entries(document)
        val removed = records.firstOrNull { it.getString("id") == id } ?: return@synchronized null
        val remaining = records.filterNot { it.getString("id") == id }
        document.put("edits", JSONArray(remaining)).put("removed", removed)
        if (document.stringOrNull("current") == id) document.put("current", remaining.maxByOrNull { it.optLong("updatedAt") }?.getString("id"))
        publish(document)
        cleanup(document)
        title(removed)
    }

    fun undoRemove(expectedId: String? = null): Boolean = synchronized(lock) {
        val document = readDocument() ?: return@synchronized false
        val removed = document.optJSONObject("removed") ?: return@synchronized false
        if (expectedId != null && removed.optString("id") != expectedId) return@synchronized false
        val records = entries(document)
        val current = document.stringOrNull("current")?.takeIf { id -> records.any { it.getString("id") == id } }
        document.put("edits", JSONArray(records + removed)).put("current", current ?: removed.getString("id"))
        document.remove("removed")
        publish(document)
        true
    }

    fun purgeRemoved(expectedId: String? = null): Unit = synchronized(lock) {
        val document = readDocument() ?: return@synchronized
        if (expectedId != null && document.optJSONObject("removed")?.optString("id") != expectedId) return@synchronized
        if (document.remove("removed") == null) return@synchronized
        publish(document)
        cleanup(document)
    }

    private fun readDocument(): JSONObject? {
        if (!manifest.baseFile.exists() && !File(directory, "current.json.bak").exists()) return null
        val document = JSONObject(manifest.readFully().toString(Charsets.UTF_8))
        if (document.has("edits")) return document
        // Migration changes only the pointer; the original preview and its settings remain intact.
        asset(document.getString("photo"), "photo-")
        val id = UUID.randomUUID().toString()
        val record = JSONObject(document.toString()).put("id", id).put("updatedAt", manifest.baseFile.lastModified())
        val migrated = JSONObject().put("version", 2).put("current", id).put("edits", JSONArray().put(record))
        publish(migrated)
        return migrated
    }

    private fun entries(document: JSONObject): List<JSONObject> {
        val array = document.getJSONArray("edits")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun publish(document: JSONObject, isCurrent: () -> Boolean = { true }): Boolean {
        val stream = manifest.startWrite()
        try {
            stream.write(document.toString().toByteArray(Charsets.UTF_8))
            if (!isCurrent()) { manifest.failWrite(stream); return false }
            manifest.finishWrite(stream)
            return true
        } catch (error: Throwable) {
            manifest.failWrite(stream)
            throw error
        }
    }

    private fun asset(name: String, prefix: String): File {
        require(name == File(name).name && name.startsWith(prefix) && !name.contains('\\')) { "Invalid saved photo path" }
        return File(directory, name)
    }

    private fun referencedAssets(document: JSONObject): Set<File> =
        (entries(document) + listOfNotNull(document.optJSONObject("removed"))).flatMap { json ->
            listOf(asset(json.getString("photo"), "photo-")) + listOfNotNull(json.stringOrNull("source")?.let { asset(it, "source-") })
        }.toSet()

    private fun cleanup(document: JSONObject) {
        val retained = referencedAssets(document)
        directory.listFiles()?.filter { (it.name.startsWith("photo-") || it.name.startsWith("source-")) && it !in retained }
            ?.forEach { it.delete() }
    }

    private fun title(json: JSONObject) = json.optJSONObject("metadata")?.stringOrNull("fileName") ?: "Photo"

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
