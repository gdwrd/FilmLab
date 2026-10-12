package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.raw.RawImageDecoder
import com.example.raw.RawMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Success(val message: String, val uri: Uri) : SaveStatus
    data class Error(val error: String) : SaveStatus
}

data class FilmLabUiState(
    val baseBitmap: Bitmap? = null,
    val filmFullBitmap: Bitmap? = null,
    val selectedPreset: FilmPreset = FilmPreset.ORIGINAL,
    val intensity: Float = 1f,
    val grainIntensity: Float = 1f,
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
)

class FilmLabViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(FilmLabUiState())
    val uiState: StateFlow<FilmLabUiState> = _uiState.asStateFlow()
    private val draft = EditDraftStore(application)
    private val draftRevision = AtomicLong()
    private var imageGeneration = 0L
    private var importJob: Job? = null
    private var filterJob: Job? = null
    private var thumbnailJob: Job? = null
    private var draftJob: Job? = null
    private var saveJob: Job? = null

    init {
        val generation = imageGeneration
        importJob = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { draft.load() } ?: return@launch
                if (generation != imageGeneration) return@launch
                _uiState.value = restored
                restored.baseBitmap?.let {
                    recomputeFilmEffect()
                    generatePreviews(it, generation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "Your last edit could not be restored. Choose a photo to start again.") }
            }
        }
    }

    fun onImagePicked(uri: Uri): Job {
        val generation = beginImport()
        return viewModelScope.launch {
            try {
                val decoded = RawImageDecoder.decodeImage(getApplication(), uri)
                val bitmap = withContext(Dispatchers.Default) { FilmEngine.normalizeTo35mmResolution(decoded.bitmap) }
                currentCoroutineContext().ensureActive()
                if (generation == imageGeneration) setPhoto(bitmap, decoded.metadata, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == imageGeneration) {
                    _uiState.update { it.copy(isImporting = false, isProcessing = false, errorMessage = "This photo could not be opened. Try another photo or a JPEG exported by your camera.") }
                    restoreRetainedPreview()
                }
            }
        }.also { importJob = it }
    }

    private fun beginImport(): Long {
        imageGeneration++
        importJob?.cancel()
        filterJob?.cancel()
        thumbnailJob?.cancel()
        _uiState.update { it.copy(isImporting = true, isProcessing = false, isComparing = false, errorMessage = null) }
        return imageGeneration
    }

    private fun setPhoto(bitmap: Bitmap, metadata: RawMetadata?, generation: Long, sampleId: String? = null) {
        _uiState.value = FilmLabUiState(
            baseBitmap = bitmap, filmFullBitmap = bitmap, rawMetadata = metadata,
            imageWidth = bitmap.width, imageHeight = bitmap.height,
            activeSampleId = sampleId, showWelcome = false,
            saveStatus = if (saveJob?.isActive == true) SaveStatus.Saving else SaveStatus.Idle
        )
        persistDraft(immediately = true)
        generatePreviews(bitmap, generation)
    }

    fun loadSampleSync(sampleId: String) {
        val generation = beginImport()
        setPhoto(
            FilmEngine.normalizeTo35mmResolution(SamplePhotos.generateSampleBitmap(sampleId)),
            SamplePhotos.getSampleMetadata(sampleId), generation, sampleId
        )
    }

    fun loadSample(sampleId: String): Job {
        val generation = beginImport()
        return viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { SamplePhotos.generateSampleBitmap(sampleId) }
            if (generation == imageGeneration) setPhoto(bitmap, SamplePhotos.getSampleMetadata(sampleId), generation, sampleId)
        }.also { importJob = it }
    }

    fun selectPreset(preset: FilmPreset) {
        if (_uiState.value.selectedPreset == preset) return
        _uiState.update { it.copy(selectedPreset = preset, isComparing = false) }
        recomputeFilmEffect()
        persistDraft()
    }

    fun setIntensity(intensity: Float) {
        if (!intensity.isFinite()) return
        _uiState.update { it.copy(intensity = intensity.coerceIn(0f, 1f), isComparing = false) }
        persistDraft()
    }

    fun setGrainIntensity(grainIntensity: Float) {
        if (!grainIntensity.isFinite()) return
        _uiState.update { it.copy(grainIntensity = grainIntensity.coerceIn(0f, 2f), isComparing = false) }
        recomputeFilmEffect(debounce = true)
        persistDraft()
    }

    fun resetGrainIntensity() {
        _uiState.update { it.copy(grainIntensity = 1f, isComparing = false) }
        recomputeFilmEffect()
        persistDraft()
    }

    fun resetAdjustments() {
        _uiState.update { it.copy(intensity = 1f, grainIntensity = 1f, isComparing = false) }
        recomputeFilmEffect()
        persistDraft()
    }

    fun setComparing(comparing: Boolean) {
        _uiState.update { it.copy(isComparing = comparing && it.baseBitmap != null) }
    }

    fun openWelcome() {
        val cancelledImport = _uiState.value.isImporting
        if (_uiState.value.isImporting) {
            imageGeneration++
            importJob?.cancel()
        }
        _uiState.update { it.copy(showWelcome = true, isComparing = false, isImporting = false) }
        if (cancelledImport) restoreRetainedPreview()
        persistDraft(immediately = true)
    }

    fun resumeEditing() {
        if (_uiState.value.baseBitmap != null) _uiState.update { it.copy(showWelcome = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissSaveStatus() {
        if (saveJob?.isActive != true) _uiState.update { it.copy(saveStatus = SaveStatus.Idle) }
    }

    private fun restoreRetainedPreview() {
        _uiState.value.baseBitmap?.let {
            recomputeFilmEffect()
            generatePreviews(it, imageGeneration)
        }
    }

    private fun recomputeFilmEffect(debounce: Boolean = false) {
        filterJob?.cancel()
        val snapshot = _uiState.value
        val base = snapshot.baseBitmap ?: return
        val generation = imageGeneration
        if (snapshot.selectedPreset == FilmPreset.ORIGINAL) {
            _uiState.update { it.copy(filmFullBitmap = base, isProcessing = false) }
            return
        }
        _uiState.update { it.copy(isProcessing = true) }
        filterJob = viewModelScope.launch {
            try {
                if (debounce) delay(80)
                val processed = FilmEngine.applyFilmFilter(base, snapshot.selectedPreset, 1f, snapshot.grainIntensity)
                currentCoroutineContext().ensureActive()
                if (generation == imageGeneration) {
                    _uiState.update { it.copy(filmFullBitmap = processed, isProcessing = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == imageGeneration) {
                    _uiState.update { it.copy(selectedPreset = FilmPreset.ORIGINAL, filmFullBitmap = base, isProcessing = false, errorMessage = "This film could not be applied. Your original photo is still available.") }
                    persistDraft()
                }
            }
        }
    }

    private fun generatePreviews(bitmap: Bitmap, generation: Long) {
        thumbnailJob?.cancel()
        thumbnailJob = viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.Default) {
                    val scale = minOf(1f, 192f / max(bitmap.width, bitmap.height))
                    Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
                }
                for (preset in FilmPreset.entries) {
                    currentCoroutineContext().ensureActive()
                    // Grain is subpixel in the strip; avoid amplifying it on the small preview.
                    val rendered = if (preset == FilmPreset.ORIGINAL) preview else FilmEngine.applyFilmFilter(preview, preset, 1f, 0f)
                    if (generation != imageGeneration) return@launch
                    _uiState.update { it.copy(presetPreviews = it.presetPreviews + (preset to rendered)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The full editor remains usable if a thumbnail cannot be rendered.
            }
        }
    }

    private fun persistDraft(immediately: Boolean = false) {
        val revision = draftRevision.incrementAndGet()
        draftJob?.cancel()
        val snapshot = _uiState.value
        if (snapshot.baseBitmap == null) return
        draftJob = viewModelScope.launch {
            try {
                if (!immediately) delay(200)
                withContext(Dispatchers.IO) { draft.save(snapshot) { draftRevision.get() == revision } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (draftRevision.get() == revision) {
                    _uiState.update { it.copy(errorMessage = "Your edit could not be kept on this device. Save a copy before leaving.") }
                }
            }
        }
    }

    suspend fun renderCurrentMasterBitmap(): Bitmap? = renderSnapshot(_uiState.value)

    private suspend fun renderSnapshot(state: FilmLabUiState): Bitmap? = withContext(Dispatchers.Default) {
        val base = state.baseBitmap ?: return@withContext null
        if (state.selectedPreset == FilmPreset.ORIGINAL || state.intensity <= 0f) return@withContext base
        val film = state.filmFullBitmap ?: return@withContext null
        if (state.intensity >= 1f) return@withContext film
        Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).apply {
                drawBitmap(base, 0f, 0f, null)
                drawBitmap(film, 0f, 0f, Paint().apply { alpha = (state.intensity * 255).roundToInt() })
            }
        }
    }

    fun saveToGallery(context: Context) {
        val snapshot = _uiState.value
        if (snapshot.baseBitmap == null || snapshot.isImporting || snapshot.isProcessing || saveJob?.isActive == true) return
        _uiState.update { it.copy(saveStatus = SaveStatus.Saving) }
        saveJob = viewModelScope.launch {
            try {
                val bitmap = renderSnapshot(snapshot) ?: error("No photo available")
                val filename = "FilmLab_" + snapshot.selectedPreset.shortCode + "_" + System.currentTimeMillis() + ".jpg"
                val uri = GalleryExporter.save(context.applicationContext, bitmap, filename)
                _uiState.update { it.copy(saveStatus = SaveStatus.Success("Saved to gallery", uri)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(saveStatus = SaveStatus.Error("The photo could not be saved. Check available storage and try again.")) }
            }
        }
    }
}
