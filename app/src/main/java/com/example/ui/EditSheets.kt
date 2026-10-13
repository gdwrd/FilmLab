package com.example.ui

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.film.FilmPreset
import com.example.film.PhotoRenderer
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun RecentEditsList(edits: List<RecentEdit>, onOpen: (String) -> Unit, onRemove: (String) -> Unit) {
    if (edits.isEmpty()) return
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent edits", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text("${edits.size} on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        edits.forEach { edit ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onOpen(edit.id) }
                    .testTag("recent_${edit.id}").padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    edit.thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(edit.title, style = MaterialTheme.typography.bodyMedium)
                        Text(edit.preset.filmName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton({ onRemove(edit.id) }, Modifier.size(48.dp).testTag("remove_recent_${edit.id}")) {
                    Icon(Icons.Default.DeleteOutline, "Remove ${edit.title}")
                }
            }
        }
    }
}

@Composable
internal fun AllLooksScreen(
    state: FilmLabUiState, initialMyLooks: Boolean, onBack: () -> Unit, onPreset: (FilmPreset) -> Unit,
    onFavourite: (FilmPreset) -> Unit, onApplyLook: (String) -> Unit,
    onSaveLook: () -> Unit, onManageLook: (String) -> Unit
) {
    var myLooks by rememberSaveable(initialMyLooks) { mutableStateOf(initialMyLooks) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("all_looks_screen")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to editor") }
            Text("All looks", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!myLooks, { myLooks = false }, { Text("Films") }, Modifier.testTag("films_tab"))
            FilterChip(myLooks, { myLooks = true }, { Text("My looks") }, Modifier.testTag("my_looks_tab"))
        }
        if (myLooks) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                if (state.savedLooks.isEmpty()) Text("Save your first look to use it on another photo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.savedLooks.forEach { look ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onApplyLook(look.id) }
                            .testTag("apply_look_${look.id}").padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SavedLookPreview(state, look)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(look.name, style = MaterialTheme.typography.titleSmall)
                                Text("${look.settings.preset.filmName()} · ${(look.settings.strength * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Grain ${(look.settings.grainIntensity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton({ onManageLook(look.id) }, Modifier.size(48.dp).testTag("manage_look_${look.id}")) {
                            Icon(Icons.Default.MoreHoriz, "Manage ${look.name}")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onSaveLook, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_current_look_button")) {
                    Icon(Icons.Default.BookmarkAdd, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Save current look")
                }
                Text("Colour and texture are saved. Framing stays with each photo.", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FilterChip(favouritesOnly, { favouritesOnly = !favouritesOnly }, { Text("Favourites") },
                Modifier.padding(horizontal = 20.dp).testTag("favourites_filter"),
                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) })
            val shown = filmOrder.filter { !favouritesOnly || it in state.favouritePresets }
            if (shown.isEmpty()) {
                Column(Modifier.weight(1f).padding(24.dp)) {
                    Text("Star a film to keep it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ favouritesOnly = false }) { Text("Show all films") }
                }
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shown, key = { it.name }) { preset ->
                    Column {
                        Box(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .selectable(state.selectedPreset == preset, role = Role.RadioButton, onClick = { onPreset(preset) })
                                .semantics { contentDescription = "Apply ${preset.filmName()}" }.testTag("all_preset_${preset.shortCode.lowercase()}")) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    state.presetPreviews[preset]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    if (state.selectedPreset == preset) Icon(Icons.Default.Check, null,
                                        Modifier.align(Alignment.BottomStart).padding(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                                Text(preset.filmName(), Modifier.padding(8.dp), style = MaterialTheme.typography.titleSmall)
                            }
                            if (preset != FilmPreset.ORIGINAL) IconButton({ onFavourite(preset) },
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape)
                                    .semantics { stateDescription = if (preset in state.favouritePresets) "Favourite" else "Not a favourite" }
                                    .testTag("favourite_${preset.shortCode.lowercase()}")) {
                                Icon(if (preset in state.favouritePresets) Icons.Default.Star else Icons.Default.StarOutline,
                                    "${if (preset in state.favouritePresets) "Remove" else "Add"} ${preset.filmName()} ${if (preset in state.favouritePresets) "from" else "to"} favourites")
                            }
                        }
                        Text(preset.filmDescription(), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Artistic film-inspired looks. Previews use your photo.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SavedLookPreview(state: FilmLabUiState, look: SavedLook) {
    val base = state.baseBitmap ?: return
    val bitmap by produceState<Bitmap?>(null, base, look.settings, state.crop) {
        value = withContext(Dispatchers.Default) {
            val scale = min(1f, 128f / max(base.width, base.height))
            val small = Bitmap.createScaledBitmap(base, (base.width * scale).roundToInt().coerceAtLeast(1), (base.height * scale).roundToInt().coerceAtLeast(1), true)
            val result = PhotoRenderer.renderPreview(small, look.settings.copy(crop = state.crop))
            if (small !== base && small !== result) small.recycle()
            result
        }
    }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdjustSheet(
    state: FilmLabUiState, onExposure: (Float) -> Unit, onWarmth: (Float) -> Unit,
    onGrain: (Float) -> Unit, onGlow: (Float) -> Unit, onStart: () -> Unit, onFinish: () -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit
) {
    EditSheet(onDismiss) {
        SheetHeader("Adjust", onDismiss)
        AdjustmentSlider("Exposure", formatSigned(state.exposure) + " EV", state.exposure, -2f..2f, onExposure, onStart, onFinish, "exposure_slider")
        AdjustmentSlider("Warmth", if (state.warmth == 0f) "Neutral" else formatSigned(state.warmth * 50f, 0), state.warmth, -1f..1f, onWarmth, onStart, onFinish, "warmth_slider")
        AdjustmentSlider("Grain", "${(state.grainIntensity * 100).roundToInt()}%", state.grainIntensity, 0f..2f, onGrain, onStart, onFinish, "grain_slider", state.selectedPreset != FilmPreset.ORIGINAL)
        if (state.selectedPreset == FilmPreset.CINESTILL_800T) AdjustmentSlider("Glow", "${(state.glowIntensity * 100).roundToInt()}%", state.glowIntensity, 0f..1f, onGlow, onStart, onFinish, "glow_slider")
        if (state.selectedPreset == FilmPreset.ORIGINAL) Text("Choose a film look to add grain.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onReset, Modifier.heightIn(min = 48.dp).testTag("reset_adjustments_button")) { Text("Reset adjustments") }
        Text("Film and crop stay as you chose them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AdjustmentSlider(
    label: String, formatted: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, onStart: () -> Unit, onFinish: () -> Unit, tag: String, enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Text(formatted, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value, { onStart(); onChange(it) }, onValueChangeFinished = onFinish, valueRange = range, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (label == "Grain") "Grain amount" else label }.testTag(tag))
}

@Composable
internal fun PhotoOptionsSheet(onSaveLook: () -> Unit, onShare: () -> Unit, onInfo: () -> Unit, onDismiss: () -> Unit) {
    EditSheet(onDismiss) {
        SheetHeader("Photo options", onDismiss)
        SheetAction("Save look", Icons.Default.BookmarkAdd, onSaveLook, "save_look_button")
        SheetAction("Share photo", Icons.Default.Share, onShare, "share_photo_button")
        SheetAction("Photo info", Icons.Default.Info, onInfo, "photo_info_button")
    }
}

@Composable
internal fun ManageLookSheet(look: SavedLook, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    EditSheet(onDismiss) {
        SheetHeader(look.name, onDismiss)
        SheetAction("Rename look", Icons.Default.Edit, onRename, "rename_look_button")
        SheetAction("Delete look", Icons.Default.DeleteOutline, onDelete, "delete_look_button")
        Text("Removing a look keeps your edited photographs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SaveLookSheet(state: FilmLabUiState, existing: SavedLook?, onSave: (String) -> String?, onSaved: () -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "Everyday ${state.selectedPreset.filmName()}") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val submit = { error = onSave(name); if (error == null) onSaved() }
    EditSheet(onDismiss) {
        SheetHeader(if (existing == null) "Save look" else "Rename look", onDismiss)
        OutlinedTextField(name, { name = it; error = null }, label = { Text("Look name") }, singleLine = true,
            isError = error != null, supportingText = { Text(error ?: "${name.length}/40") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("look_name_field"))
        if (existing == null) {
            SummaryRow("Film", state.selectedPreset.filmName())
            SummaryRow("Strength · Grain", "${(state.intensity * 100).roundToInt()}% · ${(state.grainIntensity * 100).roundToInt()}%")
            SummaryRow("Exposure · Warmth", "${formatSigned(state.exposure)} EV · ${formatSigned(state.warmth * 50, 0)}")
            if (state.selectedPreset == FilmPreset.CINESTILL_800T) SummaryRow("Glow", "${(state.glowIntensity * 100).roundToInt()}%")
            Text("Colour and texture are saved. Crop stays with this photo.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(submit, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirm_save_look_button"), colors = actionButtonColors()) {
            Text(if (existing == null) "Save look" else "Rename look")
        }
    }
}

@Composable
internal fun ExportSheet(
    state: FilmLabUiState, onSize: (ExportSize) -> Unit, onSave: () -> Unit, onShare: () -> Unit,
    onView: (Uri) -> Unit, onReopen: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit
) {
    val info = state.exportInfo
    val ready = !state.isProcessing && !state.isImporting
    val originalWidth = state.sourceWidth.takeIf { it > 0 } ?: state.baseBitmap!!.width
    val originalHeight = state.sourceHeight.takeIf { it > 0 } ?: state.baseBitmap!!.height
    val largest = PhotoRenderer.dimensions(originalWidth, originalHeight, state.crop)
    val smaller = PhotoRenderer.dimensions(state.baseBitmap!!.width, state.baseBitmap.height, state.crop, 2048)
    EditSheet(if (state.saveStatus is SaveStatus.Saving) onCancel else onDismiss) {
        when (val status = state.saveStatus) {
            is SaveStatus.Saving -> {
                SheetHeader("Save copy", onCancel, "Cancel")
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Rendering your copy…", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
                    info?.let { Text("${it.width} × ${it.height} pixels", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                OutlinedButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel_export_button")) { Text("Cancel") }
            }
            is SaveStatus.Success -> {
                SheetHeader("Saved copy", onDismiss)
                ExportUpdateNotice(state)
                Icon(Icons.Default.CheckCircleOutline, null, Modifier.align(Alignment.CenterHorizontally).size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Saved to your gallery.", Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                ExportPreview(state.copy(filmFullBitmap = status.preview ?: state.filmFullBitmap))
                val savedWidth = status.width.takeIf { it > 0 } ?: info?.width
                val savedHeight = status.height.takeIf { it > 0 } ?: info?.height
                if (savedWidth != null && savedHeight != null) Text("JPEG · $savedWidth × $savedHeight pixels", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExportActions("View copy", { onView(status.uri) }, "Share copy", onShare, ready)
                Text("Your original photo stays untouched.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SaveStatus.Error -> {
                SheetHeader("Save copy", onDismiss)
                ExportUpdateNotice(state)
                Text(status.error, Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Assertive }.testTag("export_error"), color = MaterialTheme.colorScheme.error)
                Button(onRetry, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export_retry_button"), enabled = ready, colors = actionButtonColors()) { Text("Try again") }
                if (state.exportSize == ExportSize.LARGEST) OutlinedButton({ onSize(ExportSize.SMALLER) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use smaller copy") }
            }
            else -> {
                SheetHeader("Save & share", onDismiss)
                ExportUpdateNotice(state)
                ExportPreview(state)
                Text("${state.selectedPreset.filmName()} · ${(state.intensity * 100).roundToInt()}% · JPEG", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info?.sourceAvailable == false) {
                    SummaryRow("Editing preview", "${info.width} × ${info.height} pixels")
                    TextButton(onReopen, Modifier.heightIn(min = 48.dp).testTag("reopen_original_button")) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Reopen original")
                    }
                } else {
                    ExportSizeOption(if (info?.isRawPreview == true) "Largest camera preview" else "Largest available", largest,
                        state.exportSize == ExportSize.LARGEST, { onSize(ExportSize.LARGEST) }, "export_largest")
                    if (largest != smaller) ExportSizeOption("Smaller copy", smaller, state.exportSize == ExportSize.SMALLER,
                        { onSize(ExportSize.SMALLER) }, "export_smaller")
                }
                Text(info?.limitation ?: when {
                    info?.sourceAvailable == false -> "Original unavailable. Save the editing preview, or reopen the photo for more detail."
                    info?.isRawPreview == true -> "Camera preview. RAW sensor data was not decoded."
                    else -> "Rendered from the original. Your photo is never enlarged."
                }, Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExportActions(if (info?.sourceAvailable == false) "Save preview" else "Save copy", onSave, "Share", onShare, ready)
            }
        }
    }
}

@Composable
private fun ExportUpdateNotice(state: FilmLabUiState) {
    if (!state.isProcessing && !state.isImporting) return
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("export_update_notice"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(if (state.isImporting) "Opening photo…" else "Updating photo…", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExportPreview(state: FilmLabUiState) {
    val bitmap = state.filmFullBitmap ?: state.baseBitmap ?: return
    Image(bitmap.asImageBitmap(), "Edited copy", Modifier.fillMaxWidth().heightIn(max = 190.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height)
        .clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
}

@Composable
private fun ExportSizeOption(label: String, dimensions: Pair<Int, Int>, selected: Boolean, onClick: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .testTag(tag).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${dimensions.first} × ${dimensions.second} pixels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportActions(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit, enabled: Boolean) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onPrimary, Modifier.heightIn(min = 48.dp).testTag("export_save_button"), enabled = enabled, colors = actionButtonColors()) { Text(primary) }
        OutlinedButton(onSecondary, Modifier.heightIn(min = 48.dp).testTag("export_share_button"), enabled = enabled) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(secondary) }
    }
}

@Composable
private fun SheetAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, tag: String) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag), contentPadding = PaddingValues(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(16.dp)); Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), content = content)
    }
}

internal fun formatSigned(value: Float, decimals: Int = 1): String = String.format(Locale.UK, "%s%.${decimals}f", if (value > 0) "+" else "", value)
