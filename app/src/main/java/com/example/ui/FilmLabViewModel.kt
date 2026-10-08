package com.example.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed interface SaveStatus {
    object Idle : SaveStatus
    object Saving : SaveStatus
    data class Success(val message: String) : SaveStatus
    data class Error(val error: String) : SaveStatus
}

data class FilmLabUiState(
    val baseBitmap: Bitmap? = null,
    val filmFullBitmap: Bitmap? = null,
    val selectedPreset: FilmPreset = FilmPreset.KODACHROME_64,
    val intensity: Float = 1.0f,
    val isComparing: Boolean = false,
    val isProcessing: Boolean = false,
    val activeSampleId: String? = null,
    val imageWidth: Int = 2048,
    val imageHeight: Int = 1365,
    val saveStatus: SaveStatus = SaveStatus.Idle,
    val showInfoSheet: Boolean = false
)

class FilmLabViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FilmLabUiState())
    val uiState: StateFlow<FilmLabUiState> = _uiState.asStateFlow()

    private var filterJob: Job? = null

    init {
        // App launches cleanly ready for the user's gallery photo
    }

    fun loadSample(sampleId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, activeSampleId = sampleId) }
            val rawSample = withContext(Dispatchers.Default) {
                SamplePhotos.generateSampleBitmap(sampleId)
            }
            val normalized = withContext(Dispatchers.Default) {
                FilmEngine.normalizeTo35mmResolution(rawSample)
            }
            _uiState.update {
                it.copy(
                    baseBitmap = normalized,
                    imageWidth = normalized.width,
                    imageHeight = normalized.height,
                    activeSampleId = sampleId
                )
            }
            recomputeFilmEffect(normalized, _uiState.value.selectedPreset)
        }
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, activeSampleId = null) }
            try {
                val context = getApplication<Application>()
                val loadedBitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }

                if (loadedBitmap != null) {
                    val normalized = withContext(Dispatchers.Default) {
                        FilmEngine.normalizeTo35mmResolution(loadedBitmap)
                    }
                    _uiState.update {
                        it.copy(
                            baseBitmap = normalized,
                            imageWidth = normalized.width,
                            imageHeight = normalized.height,
                            activeSampleId = null
                        )
                    }
                    recomputeFilmEffect(normalized, _uiState.value.selectedPreset)
                } else {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            saveStatus = SaveStatus.Error("Failed to decode image from gallery")
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        saveStatus = SaveStatus.Error("Error loading image: ${e.localizedMessage}")
                    )
                }
            }
        }
    }

    fun selectPreset(preset: FilmPreset) {
        if (_uiState.value.selectedPreset == preset && _uiState.value.filmFullBitmap != null) {
            return
        }
        _uiState.update { it.copy(selectedPreset = preset) }
        val base = _uiState.value.baseBitmap ?: return
        recomputeFilmEffect(base, preset)
    }

    fun setIntensity(intensity: Float) {
        _uiState.update { it.copy(intensity = intensity.coerceIn(0f, 1f)) }
    }

    fun setComparing(comparing: Boolean) {
        _uiState.update { it.copy(isComparing = comparing) }
    }

    fun toggleInfoSheet(show: Boolean) {
        _uiState.update { it.copy(showInfoSheet = show) }
    }

    fun dismissSaveStatus() {
        _uiState.update { it.copy(saveStatus = SaveStatus.Idle) }
    }

    private fun recomputeFilmEffect(base: Bitmap, preset: FilmPreset) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            if (preset == FilmPreset.ORIGINAL) {
                _uiState.update {
                    it.copy(
                        filmFullBitmap = base,
                        isProcessing = false
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isProcessing = true) }
            val processed = FilmEngine.applyFilmFilter(base, preset, 1.0f)
            _uiState.update {
                it.copy(
                    filmFullBitmap = processed,
                    isProcessing = false
                )
            }
        }
    }

    /**
     * Renders the final blended 2048px master bitmap based on current preset and intensity.
     */
    suspend fun renderCurrentMasterBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        val state = _uiState.value
        val base = state.baseBitmap ?: return@withContext null
        if (state.selectedPreset == FilmPreset.ORIGINAL || state.intensity <= 0.001f) {
            return@withContext base
        }
        return@withContext FilmEngine.applyFilmFilter(base, state.selectedPreset, state.intensity)
    }

    fun saveToGallery(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(saveStatus = SaveStatus.Saving) }
            try {
                val masterBitmap = renderCurrentMasterBitmap()
                if (masterBitmap == null) {
                    _uiState.update { it.copy(saveStatus = SaveStatus.Error("No image available to save")) }
                    return@launch
                }

                val preset = _uiState.value.selectedPreset
                val filename = "FilmLab_${preset.shortCode}_${System.currentTimeMillis()}.jpg"

                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FilmLab")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }

                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            ?: throw IllegalStateException("Could not create MediaStore entry")

                        resolver.openOutputStream(uri)?.use { stream ->
                            masterBitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)
                        }

                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    } else {
                        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val filmLabDir = File(picturesDir, "FilmLab").apply { mkdirs() }
                        val file = File(filmLabDir, filename)
                        FileOutputStream(file).use { out ->
                            masterBitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        saveStatus = SaveStatus.Success(
                            "Saved 35mm scan (${masterBitmap.width}x${masterBitmap.height}) to Pictures/FilmLab"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(saveStatus = SaveStatus.Error("Failed to save: ${e.localizedMessage}"))
                }
            }
        }
    }

    fun shareImage(context: Context) {
        viewModelScope.launch {
            try {
                val masterBitmap = renderCurrentMasterBitmap() ?: return@launch
                val uri = withContext(Dispatchers.IO) {
                    val cacheDir = File(context.cacheDir, "images").apply { mkdirs() }
                    val file = File(cacheDir, "filmlab_share_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        masterBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "35mm film scan developed with FilmLab (${_uiState.value.selectedPreset.displayName})"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Share 35mm Film Scan")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(saveStatus = SaveStatus.Error("Failed to share image: ${e.localizedMessage}"))
                }
            }
        }
    }
}
