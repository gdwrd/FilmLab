package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.ui.FilmLabScreen
import com.example.ui.FilmLabViewModel
import com.example.ui.theme.FilmLabTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: FilmLabViewModel by viewModels()
    private var sharedPhotoJob: Job? = null
    private var sharedPhotoDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilmLabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FilmLabScreen(viewModel = viewModel)
                }
            }
        }
        // Resume a cancelled readability check, but do not repeat an import owned by the retained ViewModel.
        sharedPhotoDelivered = savedInstanceState?.getBoolean("sharedPhotoDelivered") ?: false
        if (!sharedPhotoDelivered) openSharedPhoto(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("sharedPhotoDelivered", sharedPhotoDelivered)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSharedPhoto(intent)
    }

    private fun openSharedPhoto(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        sharedPhotoDelivered = false
        sharedPhotoJob?.cancel()
        val uri = sharedImageUri(intent)
        if (uri == null) {
            sharedPhotoDelivered = true
            sharedPhotoUnavailable()
            return
        }
        sharedPhotoJob = lifecycleScope.launch {
            val readable = try {
                withContext(Dispatchers.IO) {
                    val type = contentResolver.getType(uri)
                    val supportedType = type == null || type.startsWith("image/", ignoreCase = true) ||
                        type.equals("application/octet-stream", ignoreCase = true)
                    supportedType && contentResolver.openInputStream(uri)?.use { true } == true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            sharedPhotoDelivered = true
            if (readable) viewModel.onImagePicked(uri) else sharedPhotoUnavailable()
        }
    }

    private fun sharedPhotoUnavailable() {
        Toast.makeText(this, "This shared photo could not be opened. Try sharing a JPEG or choose it from FilmLab.", Toast.LENGTH_LONG).show()
    }
}

internal fun sharedImageUri(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/", ignoreCase = true) != true) return null
    return try {
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        } ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        uri?.takeIf { it.scheme.equals("content", ignoreCase = true) && !it.authority.isNullOrBlank() }
    } catch (_: RuntimeException) {
        null
    }
}
