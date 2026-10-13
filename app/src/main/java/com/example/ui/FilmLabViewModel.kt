package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.film.CropSettings
import com.example.film.EditSettings
import com.example.film.PhotoRenderer
import com.example.raw.RawImageDecoder
import com.example.raw.RawMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Success(val message: String, val uri: Uri, val preview: Bitmap? = null, val width: Int = 0, val height: Int = 0) : SaveStatus
    data class Error(val error: String) : SaveStatus
}

enum class ExportSize { LARGEST, SMALLER }

data class RecentEdit(val id: String, val title: String, val preset: FilmPreset, val thumbnail: Bitmap?, val updatedAt: Long)
data class SavedLook(val id: String, val name: String, val settings: EditSettings)
data class ExportInfo(val width: Int, val height: Int, val sourceAvailable: Boolean, val isRawPreview: Boolean, val limitation: String? = null)

data class FilmLabUiState(
    val baseBitmap: Bitmap? = null,
    val filmFullBitmap: Bitmap? = null,
    val selectedPreset: FilmPreset = FilmPreset.ORIGINAL,
    val intensity: Float = 1f,
    val grainIntensity: Float = 1f,
    val exposure: Float = 0f,
    val warmth: Float = 0f,
    val glowIntensity: Float = 1f,
    val crop: CropSettings = CropSettings(),
    val comparisonBitmap: Bitmap? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val photoId: String? = null,
    val sourceFile: File? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val recentEdits: List<RecentEdit> = emptyList(),
    val savedLooks: List<SavedLook> = emptyList(),
    val favouritePresets: Set<FilmPreset> = emptySet(),
    val exportSize: ExportSize = ExportSize.LARGEST,
    val exportInfo: ExportInfo? = null,
    val shareUri: Uri? = null,
    val removedEditTitle: String? = null,
    val removedEditId: String? = null,
    val isComparing: Boolean = false,
    val isProcessing: Boolean = false,
    val isImporting: Boolean = false,
    val showWelcome: Boolean = true,
    val activeSampleId: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val rawMetadata: RawMetadata? = null,
    val presetPreviews: Map<FilmPreset, Bitmap> = emptyMap(),
    val errorMessage: String? = null,
    val saveStatus: SaveStatus = SaveStatus.Idle
) {
    val editSettings: EditSettings get() = EditSettings(selectedPreset, intensity, grainIntensity, exposure, warmth, glowIntensity, crop)
}

class FilmLabViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(FilmLabUiState())
    val uiState: StateFlow<FilmLabUiState> = _uiState.asStateFlow()
    private val draft = EditDraftStore(application)
    private val looks = UserLooksStore(application)
    private val preferencesLock = Mutex()
    private val recentOperations = Mutex()
    private val draftRevision = AtomicLong()
    private var imageGeneration = 0L
    private var renderGeneration = 0L
    private var preferencesRevision = 0L
    private var importJob: Job? = null
    private var filterJob: Job? = null
    private var thumbnailJob: Job? = null
    private var draftJob: Job? = null
    private var saveJob: Job? = null
    private val history = ArrayDeque<EditSettings>()
    private val future = ArrayDeque<EditSettings>()
    private var gestureStart: EditSettings? = null

    init {
        viewModelScope.launch {
            try {
                val storedLooks = withContext(Dispatchers.IO) { looks.load() }
                if (preferencesRevision == 0L) applyLooks(storedLooks)
            } catch (error: CancellationException) { throw error } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "Your saved looks could not be restored.") }
            }
        }
        val generation = imageGeneration
        importJob = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { draft.purgeRemoved(); draft.load() }
                refreshLibrary()
                if (restored != null && generation == imageGeneration) restorePhoto(restored, generation, welcome = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == imageGeneration) _uiState.update {
                    it.copy(errorMessage = "Your last edit could not be restored. Choose a photo to start again.")
                }
            }
        }
    }

    fun onImagePicked(uri: Uri): Job = importPhoto(uri, reopen = false)
    fun reopenOriginal(uri: Uri): Job = importPhoto(uri, reopen = true)

    private fun importPhoto(uri: Uri, reopen: Boolean): Job {
        finishAdjustment()
        val previous = _uiState.value
        cancelDraftWrite()
        val generation = beginImport()
        return viewModelScope.launch {
            var stagedSource: File? = null
            try {
                if (previous.baseBitmap != null) withContext(Dispatchers.IO) {
                    val writer = currentCoroutineContext()
                    draft.save(previous) { writer.isActive && generation == imageGeneration }
                }
                val decoded = RawImageDecoder.decodeImage(getApplication(), uri, keepSource = true)
                stagedSource = decoded.sourceFile
                val bitmap = withContext(Dispatchers.Default) { FilmEngine.normalizeTo35mmResolution(decoded.bitmap) }
                if (bitmap !== decoded.bitmap) decoded.bitmap.recycle()
                currentCoroutineContext().ensureActive()
                if (generation != imageGeneration) return@launch
                val photo = FilmLabUiState(
                    baseBitmap = bitmap, filmFullBitmap = bitmap, comparisonBitmap = bitmap,
                    photoId = if (reopen) previous.photoId ?: UUID.randomUUID().toString() else UUID.randomUUID().toString(),
                    sourceFile = decoded.sourceFile, sourceWidth = decoded.sourceWidth, sourceHeight = decoded.sourceHeight,
                    rawMetadata = decoded.metadata, imageWidth = bitmap.width, imageHeight = bitmap.height, showWelcome = false
                ).let { if (reopen) withSettings(it, previous.editSettings) else it }
                val retained = withContext(Dispatchers.IO) {
                    val writer = currentCoroutineContext()
                    draft.save(photo) { writer.isActive && generation == imageGeneration }
                    draft.sourceFileFor(photo.photoId)
                }
                currentCoroutineContext().ensureActive()
                if (generation != imageGeneration) return@launch
                clearHistory()
                val library = _uiState.value
                _uiState.value = photo.copy(
                    sourceFile = retained, recentEdits = library.recentEdits,
                    savedLooks = library.savedLooks, favouritePresets = library.favouritePresets,
                    saveStatus = if (saveJob?.isActive == true) SaveStatus.Saving else SaveStatus.Idle
                )
                recomputeFilmEffect()
                generatePreviews()
                updateExportInfo()
                refreshLibrary()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == imageGeneration) {
                    val storageLimit = error.message?.takeIf { it.contains("recent edits", ignoreCase = true) || it.contains("512") || it.contains("256 MB") }
                    _uiState.update { it.copy(isImporting = false, isProcessing = false,
                        errorMessage = storageLimit ?: "This photo could not be opened. Try another photo or a JPEG exported by your camera.") }
                    restoreRetainedPreview()
                    refreshLibrary()
                }
            } finally {
                // The draft owns a durable copy; the decoder's staging file is no longer needed.
                stagedSource?.takeIf { it.parentFile == getApplication<Application>().cacheDir }?.delete()
            }
        }.also { importJob = it }
    }

    private fun beginImport(): Long {
        cancelExport()
        imageGeneration++
        importJob?.cancel()
        filterJob?.cancel()
        thumbnailJob?.cancel()
        _uiState.update { it.copy(isImporting = true, isProcessing = false, isComparing = false, errorMessage = null) }
        return imageGeneration
    }

    private suspend fun restorePhoto(restored: FilmLabUiState, generation: Long, welcome: Boolean) {
        val base = restored.baseBitmap ?: return
        val normalized = withContext(Dispatchers.Default) { FilmEngine.normalizeTo35mmResolution(base) }
        if (generation != imageGeneration) return
        val legacyRaw = restored.rawMetadata?.let {
            val note = it.processingNote.orEmpty()
            it.format.isRaw && (it.isColorRecovered || note.startsWith("Developed from RAW sensor data.") ||
                note.startsWith("Colour decoded from RAW sensor data."))
        } == true
        val metadata = if (legacyRaw) restored.rawMetadata?.copy(
            isColorRecovered = false,
            processingNote = "This draft used an earlier RAW decoder. Reopen the original photo to use its camera preview or Android's decoder."
        ) else restored.rawMetadata
        clearHistory()
        val library = _uiState.value
        _uiState.value = restored.copy(
            baseBitmap = normalized, filmFullBitmap = normalized, comparisonBitmap = normalized,
            imageWidth = normalized.width, imageHeight = normalized.height, rawMetadata = metadata,
            showWelcome = welcome, isImporting = false, isProcessing = false,
            recentEdits = library.recentEdits, savedLooks = library.savedLooks, favouritePresets = library.favouritePresets,
            removedEditTitle = library.removedEditTitle,
            removedEditId = library.removedEditId,
            saveStatus = if (saveJob?.isActive == true) SaveStatus.Saving else SaveStatus.Idle,
            errorMessage = if (legacyRaw) "This saved RAW edit used an earlier decoder. Reopen the original photo for accurate colour." else null
        )
        if (normalized !== base || legacyRaw) persistDraft(immediately = true)
        recomputeFilmEffect()
        generatePreviews()
        updateExportInfo()
    }

    fun loadSampleSync(sampleId: String) {
        val generation = beginImport()
        val bitmap = FilmEngine.normalizeTo35mmResolution(SamplePhotos.generateSampleBitmap(sampleId))
        setSample(bitmap, sampleId, generation)
    }

    fun loadSample(sampleId: String): Job {
        val generation = beginImport()
        return viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                FilmEngine.normalizeTo35mmResolution(SamplePhotos.generateSampleBitmap(sampleId))
            }
            if (generation == imageGeneration) setSample(bitmap, sampleId, generation)
        }.also { importJob = it }
    }

    private fun setSample(bitmap: Bitmap, sampleId: String, generation: Long) {
        if (generation != imageGeneration) return
        clearHistory()
        val previous = _uiState.value
        _uiState.value = FilmLabUiState(
            baseBitmap = bitmap, filmFullBitmap = bitmap, comparisonBitmap = bitmap,
            photoId = UUID.randomUUID().toString(), sourceWidth = bitmap.width, sourceHeight = bitmap.height,
            rawMetadata = SamplePhotos.getSampleMetadata(sampleId), imageWidth = bitmap.width, imageHeight = bitmap.height,
            activeSampleId = sampleId, showWelcome = false, recentEdits = previous.recentEdits,
            savedLooks = previous.savedLooks, favouritePresets = previous.favouritePresets
        )
        persistDraft(immediately = true)
        generatePreviews()
        updateExportInfo()
    }

    fun beginAdjustment() {
        if (gestureStart == null) gestureStart = _uiState.value.editSettings
    }

    fun finishAdjustment() {
        val before = gestureStart ?: return
        gestureStart = null
        rememberChange(before)
        persistDraft(immediately = true)
        generatePreviews()
    }

    private fun rememberChange(before: EditSettings) {
        if (before == _uiState.value.editSettings) return
        history.addLast(before)
        if (history.size > 30) history.removeFirst()
        future.clear()
        updateHistoryState()
    }

    private fun clearHistory() { history.clear(); future.clear(); gestureStart = null }
    private fun updateHistoryState() { _uiState.update { it.copy(canUndo = history.isNotEmpty(), canRedo = future.isNotEmpty()) } }

    private fun withSettings(state: FilmLabUiState, settings: EditSettings): FilmLabUiState {
        val edit = settings.normalized()
        return state.copy(selectedPreset = edit.preset, intensity = edit.strength, grainIntensity = edit.grainIntensity,
            exposure = edit.exposure, warmth = edit.warmth, glowIntensity = edit.glowIntensity, crop = edit.crop, isComparing = false)
    }

    private fun changeSettings(settings: EditSettings) {
        finishAdjustment()
        val before = _uiState.value.editSettings
        val normalized = settings.normalized()
        if (before == normalized) return
        _uiState.update { withSettings(it, normalized) }
        rememberChange(before)
        recomputeFilmEffect()
        generatePreviews()
        updateExportInfo()
        persistDraft()
    }

    fun selectPreset(preset: FilmPreset) = changeSettings(_uiState.value.editSettings.copy(preset = preset))
    fun applyCrop(crop: CropSettings) = changeSettings(_uiState.value.editSettings.copy(crop = crop))

    private fun adjust(settings: EditSettings) {
        beginAdjustment()
        _uiState.update { withSettings(it, settings) }
        recomputeFilmEffect(debounce = true)
        updateExportInfo()
        persistDraft()
    }

    fun setIntensity(value: Float) { if (value.isFinite()) adjust(_uiState.value.editSettings.copy(strength = value)) }
    fun setGrainIntensity(value: Float) { if (value.isFinite()) adjust(_uiState.value.editSettings.copy(grainIntensity = value)) }
    fun setExposure(value: Float) { if (value.isFinite()) adjust(_uiState.value.editSettings.copy(exposure = value)) }
    fun setWarmth(value: Float) { if (value.isFinite()) adjust(_uiState.value.editSettings.copy(warmth = value)) }
    fun setGlowIntensity(value: Float) { if (value.isFinite()) adjust(_uiState.value.editSettings.copy(glowIntensity = value)) }
    fun resetGrainIntensity() = changeSettings(_uiState.value.editSettings.copy(grainIntensity = 1f))
    fun resetAdjustments() = changeSettings(EditSettings(preset = _uiState.value.selectedPreset, crop = _uiState.value.crop))

    fun undo() {
        finishAdjustment()
        if (history.isEmpty()) return
        future.addLast(_uiState.value.editSettings)
        val edit = history.removeLast()
        _uiState.update { withSettings(it, edit) }
        afterHistoryChange()
    }

    fun redo() {
        finishAdjustment()
        if (future.isEmpty()) return
        history.addLast(_uiState.value.editSettings)
        val edit = future.removeLast()
        _uiState.update { withSettings(it, edit) }
        afterHistoryChange()
    }

    private fun afterHistoryChange() {
        updateHistoryState(); recomputeFilmEffect(); generatePreviews(); updateExportInfo(); persistDraft()
    }

    fun setComparing(comparing: Boolean) { _uiState.update { it.copy(isComparing = comparing && it.baseBitmap != null) } }

    fun openWelcome() {
        finishAdjustment()
        val cancelledImport = _uiState.value.isImporting
        if (cancelledImport) { imageGeneration++; importJob?.cancel() }
        _uiState.update { it.copy(showWelcome = true, isComparing = false, isImporting = false) }
        if (cancelledImport) restoreRetainedPreview()
        persistDraft(immediately = true)
    }

    fun resumeEditing() { if (_uiState.value.baseBitmap != null) _uiState.update { it.copy(showWelcome = false) } }
    fun dismissError() { _uiState.update { it.copy(errorMessage = null) } }
    fun dismissSaveStatus() { if (saveJob?.isActive != true) _uiState.update { it.copy(saveStatus = SaveStatus.Idle) } }
    fun dismissShare() { _uiState.update { it.copy(shareUri = null) } }

    private fun restoreRetainedPreview() {
        if (_uiState.value.baseBitmap != null) { recomputeFilmEffect(); generatePreviews() }
    }

    private fun recomputeFilmEffect(debounce: Boolean = false) {
        filterJob?.cancel()
        val snapshot = _uiState.value
        val base = snapshot.baseBitmap ?: return
        val generation = ++renderGeneration
        val photoGeneration = imageGeneration
        _uiState.update { it.copy(isProcessing = true) }
        filterJob = viewModelScope.launch {
            try {
                if (debounce) delay(80)
                val processed = PhotoRenderer.renderPreview(base, snapshot.editSettings)
                val comparison = withContext(Dispatchers.Default) { PhotoRenderer.cropOriginal(base, snapshot.crop) }
                currentCoroutineContext().ensureActive()
                if (generation == renderGeneration && photoGeneration == imageGeneration) _uiState.update {
                    it.copy(filmFullBitmap = processed, comparisonBitmap = comparison, isProcessing = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == renderGeneration && photoGeneration == imageGeneration) _uiState.update {
                    it.copy(isProcessing = false, errorMessage = "This edit could not be rendered. Undo the last change or reset adjustments.")
                }
            }
        }
    }

    private fun generatePreviews() {
        thumbnailJob?.cancel()
        val state = _uiState.value
        val base = state.baseBitmap ?: return
        val photoGeneration = imageGeneration
        thumbnailJob = viewModelScope.launch {
            try {
                val cropped = withContext(Dispatchers.Default) { PhotoRenderer.cropOriginal(base, state.crop) }
                val preview = withContext(Dispatchers.Default) {
                    val scale = minOf(1f, 192f / max(cropped.width, cropped.height))
                    Bitmap.createScaledBitmap(cropped, max(1, (cropped.width * scale).roundToInt()), max(1, (cropped.height * scale).roundToInt()), true)
                }
                _uiState.update { it.copy(presetPreviews = emptyMap()) }
                val adjusted = FilmEngine.applyAdjustments(preview, state.exposure, state.warmth)
                for (preset in FilmPreset.entries) {
                    currentCoroutineContext().ensureActive()
                    val rendered = when (preset) {
                        FilmPreset.ORIGINAL -> adjusted
                        FilmPreset.CINESTILL_800T -> {
                            // Render glow before shrinking so thumbnails keep the full photo's halo size.
                            val full = PhotoRenderer.renderPreview(base, state.editSettings.copy(
                                preset = preset, strength = 1f, grainIntensity = 0f
                            ))
                            val thumbnail = withContext(Dispatchers.Default) {
                                Bitmap.createScaledBitmap(full, preview.width, preview.height, true)
                            }
                            if (thumbnail !== full) full.recycle()
                            thumbnail
                        }
                        else -> FilmEngine.applyFilmFilter(adjusted, preset, 1f, 0f, glowIntensity = state.glowIntensity)
                    }
                    if (photoGeneration != imageGeneration) return@launch
                    _uiState.update { it.copy(presetPreviews = it.presetPreviews + (preset to rendered)) }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) {
                // A failed thumbnail must not block the full editor.
            }
        }
    }

    private fun cancelDraftWrite() { draftRevision.incrementAndGet(); draftJob?.cancel() }

    private fun persistDraft(immediately: Boolean = false) {
        val revision = draftRevision.incrementAndGet()
        draftJob?.cancel()
        val snapshot = _uiState.value
        if (snapshot.baseBitmap == null) return
        draftJob = viewModelScope.launch {
            try {
                if (!immediately) delay(200)
                val retained = withContext(Dispatchers.IO) {
                    val writer = currentCoroutineContext()
                    draft.save(snapshot) { writer.isActive && draftRevision.get() == revision }
                    draft.sourceFileFor(snapshot.photoId)
                }
                if (draftRevision.get() == revision) {
                    _uiState.update { if (it.photoId == snapshot.photoId) it.copy(sourceFile = retained) else it }
                    refreshLibrary()
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) {
                if (draftRevision.get() == revision) _uiState.update {
                    it.copy(errorMessage = "Your edit could not be kept on this device. Save a copy before leaving.")
                }
            }
        }
    }

    private suspend fun refreshLibrary() {
        val edits = withContext(Dispatchers.IO) { draft.list() }
        _uiState.update { it.copy(recentEdits = edits) }
    }

    fun openRecentEdit(id: String) {
        finishAdjustment()
        val previous = _uiState.value
        cancelDraftWrite()
        val generation = beginImport()
        importJob = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val writer = currentCoroutineContext()
                    if (previous.baseBitmap != null) draft.save(previous) { writer.isActive && generation == imageGeneration }
                    draft.load(id) ?: error("Edit missing")
                }
                restorePhoto(restored, generation, welcome = false)
                persistDraft(immediately = true)
                refreshLibrary()
            } catch (error: CancellationException) { throw error } catch (_: Exception) {
                if (generation == imageGeneration) {
                    _uiState.update { it.copy(isImporting = false, errorMessage = "This edit could not be opened. Choose the original photo again.") }
                    restoreRetainedPreview()
                }
            }
        }
    }

    fun removeRecentEdit(id: String) {
        if (saveJob?.isActive == true) {
            _uiState.update { it.copy(errorMessage = "Wait for this copy to finish saving before removing an edit.") }
            return
        }
        val generationAtRequest = imageGeneration
        if (_uiState.value.photoId == id) cancelDraftWrite()
        viewModelScope.launch {
            recentOperations.withLock {
            try {
                val removed = withContext(Dispatchers.IO) { draft.remove(id) } ?: return@launch
                _uiState.update { it.copy(removedEditTitle = removed, removedEditId = id) }
                if (_uiState.value.photoId == id && generationAtRequest == imageGeneration) {
                    val generation = beginImport()
                    val next = withContext(Dispatchers.IO) { draft.load() }
                    if (next == null && generation == imageGeneration) {
                        clearHistory()
                        _uiState.update { FilmLabUiState(recentEdits = it.recentEdits, savedLooks = it.savedLooks,
                            favouritePresets = it.favouritePresets, removedEditTitle = removed, removedEditId = id) }
                    } else if (next != null) restorePhoto(next, generation, welcome = true)
                }
                refreshLibrary()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _uiState.update { it.copy(errorMessage = "This edit could not be removed. Try again.") } }
            }
        }
    }

    fun undoRemoveRecentEdit() {
        val id = _uiState.value.removedEditId ?: return
        viewModelScope.launch {
            recentOperations.withLock {
            try {
                val restored = withContext(Dispatchers.IO) { if (draft.undoRemove(id)) draft.load() else null }
                _uiState.update { if (it.removedEditId == id) it.copy(removedEditTitle = null, removedEditId = null) else it }
                if (restored != null && _uiState.value.baseBitmap == null && !_uiState.value.isImporting) {
                    restorePhoto(restored, imageGeneration, welcome = true)
                }
                refreshLibrary()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _uiState.update { it.copy(errorMessage = "This edit could not be restored.") } }
            }
        }
    }

    fun dismissRemovedEdit() {
        val id = _uiState.value.removedEditId ?: return
        _uiState.update { it.copy(removedEditTitle = null, removedEditId = null) }
        viewModelScope.launch { recentOperations.withLock { withContext(Dispatchers.IO) { draft.purgeRemoved(id) } } }
    }

    private fun applyLooks(stored: UserLooksState) {
        _uiState.update { it.copy(savedLooks = stored.looks, favouritePresets = stored.favourites) }
    }

    private fun updateLooks(operation: () -> UserLooksState) {
        preferencesRevision++
        viewModelScope.launch {
            preferencesLock.withLock {
                try { applyLooks(withContext(Dispatchers.IO) { operation() }) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { _uiState.update { it.copy(errorMessage = error.message ?: "Your looks could not be kept on this device.") } }
            }
        }
    }

    fun toggleFavourite(preset: FilmPreset) = updateLooks { looks.toggleFavourite(preset) }

    fun saveLook(name: String, id: String? = null): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Give your look a name."
        if (trimmed.length > 40) return "Use a name with 40 characters or fewer."
        if (_uiState.value.savedLooks.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return "A look already has that name."
        val settings = if (id == null) _uiState.value.editSettings else _uiState.value.savedLooks.firstOrNull { it.id == id }?.settings
            ?: return "This saved look no longer exists."
        updateLooks { looks.saveLook(trimmed, settings, id) }
        return null
    }

    fun deleteLook(id: String) = updateLooks { looks.deleteLook(id) }
    fun applyLook(id: String) {
        val look = _uiState.value.savedLooks.firstOrNull { it.id == id } ?: return
        changeSettings(look.settings.copy(crop = _uiState.value.crop))
    }

    fun setExportSize(size: ExportSize) {
        if (saveJob?.isActive == true) return
        _uiState.update { it.copy(exportSize = size, saveStatus = SaveStatus.Idle) }
        updateExportInfo()
    }
    fun prepareExport() { finishAdjustment(); dismissSaveStatus(); updateExportInfo() }

    private fun updateExportInfo() {
        val state = _uiState.value
        val base = state.baseBitmap ?: return
        val available = state.sourceFile?.isFile == true
        val useSource = available && state.exportSize == ExportSize.LARGEST
        val width = if (useSource && state.sourceWidth > 0) state.sourceWidth else base.width
        val height = if (useSource && state.sourceHeight > 0) state.sourceHeight else base.height
        val (outWidth, outHeight) = PhotoRenderer.dimensions(width, height, state.crop,
            if (state.exportSize == ExportSize.SMALLER) FilmEngine.TARGET_LONG_EDGE else null)
        val raw = state.rawMetadata?.format?.isRaw == true
        val note = when {
            !available -> "Original unavailable. Save the editing preview, or reopen the original photo for more detail."
            raw -> state.rawMetadata?.processingNote ?: "Using the available camera preview. RAW sensor data was not decoded."
            else -> null
        }
        _uiState.update { it.copy(exportInfo = ExportInfo(outWidth, outHeight, available, raw, note)) }
    }

    suspend fun renderCurrentMasterBitmap(): Bitmap? {
        val snapshot = _uiState.value
        val base = snapshot.baseBitmap ?: return null
        return PhotoRenderer.renderPreview(base, snapshot.editSettings)
    }

    fun saveToGallery(context: Context) = export(context, share = false)
    fun sharePhoto(context: Context) = export(context, share = true)
    fun shareSavedCopy(uri: Uri) { _uiState.update { it.copy(shareUri = uri) } }

    private fun export(context: Context, share: Boolean) {
        finishAdjustment()
        val snapshot = _uiState.value
        if (snapshot.baseBitmap == null || snapshot.isImporting || snapshot.isProcessing || saveJob?.isActive == true) return
        _uiState.update { it.copy(saveStatus = SaveStatus.Saving, shareUri = null) }
        saveJob = viewModelScope.launch {
            var rendered: Bitmap? = null
            try {
                val source = snapshot.sourceFile?.takeIf(File::isFile)
                rendered = if (source != null && snapshot.exportSize == ExportSize.LARGEST) PhotoRenderer.renderSource(source, snapshot.editSettings)
                else PhotoRenderer.renderPreview(checkNotNull(snapshot.baseBitmap), snapshot.editSettings)
                currentCoroutineContext().ensureActive()
                val filename = "FilmLab_${snapshot.selectedPreset.shortCode}_${System.currentTimeMillis()}.jpg"
                val uri = if (share) GalleryExporter.share(context.applicationContext, rendered, filename)
                    else GalleryExporter.save(context.applicationContext, rendered, filename)
                currentCoroutineContext().ensureActive()
                _uiState.update { if (share) it.copy(saveStatus = SaveStatus.Idle, shareUri = uri)
                    else it.copy(saveStatus = SaveStatus.Success("Saved to gallery", uri,
                        snapshot.filmFullBitmap ?: snapshot.baseBitmap, rendered.width, rendered.height)) }
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val message = if (error is IllegalArgumentException && error.message?.contains("full-size export") == true) error.message!!
                    else "The photo could not be saved. Try Smaller copy, check available storage, or reopen the original photo."
                _uiState.update { it.copy(saveStatus = SaveStatus.Error(message)) }
            } finally {
                rendered?.takeIf { it !== snapshot.baseBitmap && it !== snapshot.filmFullBitmap && it !== snapshot.comparisonBitmap }?.recycle()
            }
        }
    }

    fun cancelExport() { saveJob?.cancel(); _uiState.update { it.copy(saveStatus = SaveStatus.Idle, shareUri = null) } }

    override fun onCleared() { draftRevision.incrementAndGet(); super.onCleared() }
}
