package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.film.PhotoRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoInfoSheet(state: FilmLabUiState, onDismiss: () -> Unit) {
    val photo = state.baseBitmap ?: return
    val metadata = state.rawMetadata
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)
        ) {
            SheetHeader("Photo info", onDismiss)
            metadata?.fileName?.let { InfoRow("Photo", it) }
            if (metadata != null) {
                InfoRow("Format", metadata.format.formatName)
                if (metadata.originalWidth > 0 && metadata.originalHeight > 0) {
                    InfoRow("Source dimensions", "${metadata.originalWidth} × ${metadata.originalHeight} pixels")
                }
                if (metadata.model != null || metadata.make != null) InfoRow("Camera", metadata.displayCameraName)
                metadata.lensModel?.let { InfoRow("Lens", it) }
                val exposure = listOfNotNull(metadata.focalLength, metadata.aperture, metadata.shutterSpeed, metadata.iso)
                if (exposure.isNotEmpty()) InfoRow("Exposure", exposure.joinToString(" · "))
            }
            InfoRow("Film", state.selectedPreset.filmName())
            InfoRow("Save as", "JPEG copy")
            val availableWidth = state.sourceWidth.takeIf { it > 0 } ?: photo.width
            val availableHeight = state.sourceHeight.takeIf { it > 0 } ?: photo.height
            InfoRow("Available image", "$availableWidth × $availableHeight pixels")
            val dimensions = state.exportInfo?.let { it.width to it.height } ?: PhotoRenderer.dimensions(
                if (state.exportSize == ExportSize.SMALLER) photo.width else availableWidth,
                if (state.exportSize == ExportSize.SMALLER) photo.height else availableHeight, state.crop,
                if (state.exportSize == ExportSize.SMALLER || state.exportInfo?.sourceAvailable == false) 2048 else null)
            InfoRow("Export dimensions", "${dimensions.first} × ${dimensions.second} pixels")
            Spacer(Modifier.height(16.dp))
            Text("Your original photo is never replaced.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                state.exportInfo?.limitation ?: "Largest available keeps the source detail. Smaller copy uses up to 2,048 pixels on the long edge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            metadata?.processingNote?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.25f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
