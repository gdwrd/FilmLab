package com.example.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.film.CropSettings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun FilmLabScreen(viewModel: FilmLabViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val foldable = rememberFoldableLayoutState()
    val zoom = rememberZoomPanState()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf("editor") }
    var lookId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var displayedPhotoId by remember { mutableStateOf(state.photoId) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(viewModel::onImagePicked)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::onImagePicked)
    }
    val reopen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::reopenOriginal)
    }
    val savePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.saveToGallery(context)
        else scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Allow storage access to save a copy on this Android version.")
        }
    }
    val saveCopy = {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) savePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.saveToGallery(context)
    }
    val closeSheet = {
        if (sheet == "adjust") viewModel.finishAdjustment()
        sheet = null
    }
    val openExport = {
        viewModel.prepareExport()
        sheet = "export"
    }
    val viewCopy: (android.net.Uri) -> Unit = { uri ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Saved to gallery. No photo viewer is installed.") }
        } catch (_: SecurityException) {
            scope.launch { snackbar.showSnackbar("Saved to gallery. Open your gallery to view the copy.") }
        }
    }
    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uri ->
            try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, "Edited photograph", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share photo"))
            } catch (_: ActivityNotFoundException) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("No sharing app is installed. Save a copy to your gallery instead.")
            } catch (_: SecurityException) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("This copy could not be shared. Try saving it to your gallery.")
            } finally {
                viewModel.dismissShare()
            }
        }
    }
    LaunchedEffect(state.isImporting, state.photoId) {
        if (state.isImporting || state.photoId != displayedPhotoId) {
            sheet = null
            destination = "editor"
            lookId = null
            removeId = null
        }
        displayedPhotoId = state.photoId
    }
    LaunchedEffect(state.baseBitmap, state.crop) { zoom.resetImmediate() }
    LaunchedEffect(state.errorMessage, state.saveStatus, state.removedEditTitle, state.removedEditId, sheet) {
        state.errorMessage?.let { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
            if (viewModel.uiState.value.errorMessage == message) viewModel.dismissError()
            return@LaunchedEffect
        }
        state.removedEditTitle?.let { title ->
            val removalId = state.removedEditId
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Removed $title", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (viewModel.uiState.value.removedEditId == removalId && viewModel.uiState.value.removedEditTitle == title) {
                if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoveRecentEdit()
                else viewModel.dismissRemovedEdit()
            }
            return@LaunchedEffect
        }
        if (sheet == "export") return@LaunchedEffect
        when (val result = state.saveStatus) {
            is SaveStatus.Success -> {
                if (snackbar.showSnackbar(result.message, actionLabel = "View", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    viewCopy(result.uri)
                }
                if (viewModel.uiState.value.saveStatus == result) viewModel.dismissSaveStatus()
            }
            is SaveStatus.Error -> {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(result.error)
                if (viewModel.uiState.value.saveStatus == result) viewModel.dismissSaveStatus()
            }
            else -> Unit
        }
    }
    BackHandler(enabled = sheet == null && (destination != "editor" || state.isImporting || !state.showWelcome)) {
        if (destination != "editor") destination = "editor" else viewModel.openWelcome()
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        when {
            state.isImporting -> OpeningPhoto()
            state.showWelcome || state.baseBitmap == null -> WelcomeScreen(
                state = state, foldable = foldable,
                onContinue = { destination = "editor"; viewModel.resumeEditing() },
                onChoose = { destination = "editor"; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onBrowse = { destination = "editor"; files.launch(arrayOf("*/*")) },
                onOpenRecent = { destination = "editor"; viewModel.openRecentEdit(it) },
                onRemoveRecent = { removeId = it }
            )
            destination == "looks" || destination == "mylooks" -> AllLooksScreen(
                state, initialMyLooks = destination == "mylooks", onBack = { destination = "editor" },
                onPreset = { viewModel.selectPreset(it); destination = "editor" },
                onFavourite = viewModel::toggleFavourite,
                onApplyLook = { viewModel.applyLook(it); destination = "editor" },
                onSaveLook = { lookId = null; sheet = "recipe" },
                onManageLook = { lookId = it; sheet = "manage" }
            )
            destination == "crop" -> CropScreen(
                state, foldable, onCancel = { destination = "editor" },
                onApply = { viewModel.applyCrop(it); destination = "editor" }
            )
            else -> Column(Modifier.fillMaxSize()) {
                EditorToolbar(
                    saving = state.saveStatus is SaveStatus.Saving,
                    canSave = !state.isProcessing && !state.isImporting,
                    canUndo = state.canUndo, canRedo = state.canRedo,
                    onBack = viewModel::openWelcome, onSave = openExport,
                    onUndo = viewModel::undo, onRedo = viewModel::redo
                )
                EditorLayout(
                    state, foldable, zoom,
                    onPreset = { viewModel.setComparing(false); viewModel.selectPreset(it) },
                    onStrength = viewModel::setIntensity,
                    onStrengthStart = viewModel::beginAdjustment, onStrengthEnd = viewModel::finishAdjustment,
                    onCompare = viewModel::setComparing,
                    onAdjust = { viewModel.setComparing(false); sheet = "adjust" },
                    onLooks = { destination = "looks" },
                    onCrop = { viewModel.setComparing(false); destination = "crop" },
                    onMore = { sheet = "more" }, modifier = Modifier.weight(1f)
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    if (!state.isImporting) {
        if (sheet == "adjust" && state.baseBitmap != null) {
            AdjustSheet(state, viewModel::setExposure, viewModel::setWarmth, viewModel::setGrainIntensity,
                viewModel::setGlowIntensity, viewModel::beginAdjustment, viewModel::finishAdjustment,
                viewModel::resetAdjustments, closeSheet)
        }
        if (sheet == "info" && state.baseBitmap != null) PhotoInfoSheet(state, closeSheet)
        if (sheet == "more") PhotoOptionsSheet(
            onSaveLook = { lookId = null; sheet = "recipe" },
            onShare = openExport, onInfo = { viewModel.prepareExport(); sheet = "info" }, onDismiss = closeSheet
        )
        if (sheet == "recipe") SaveLookSheet(
            state, state.savedLooks.firstOrNull { it.id == lookId },
            onSave = { name -> viewModel.saveLook(name, lookId) },
            onSaved = { sheet = null; destination = "mylooks" }, onDismiss = closeSheet
        )
        if (sheet == "manage") state.savedLooks.firstOrNull { it.id == lookId }?.let { look ->
            ManageLookSheet(look, onRename = { sheet = "recipe" },
                onDelete = { viewModel.deleteLook(look.id); sheet = null }, onDismiss = closeSheet)
        }
        if (sheet == "export" && state.baseBitmap != null) ExportSheet(
            state, onSize = viewModel::setExportSize, onSave = saveCopy,
            onShare = {
                val saved = state.saveStatus as? SaveStatus.Success
                if (saved != null) viewModel.shareSavedCopy(saved.uri) else viewModel.sharePhoto(context)
            }, onView = viewCopy,
            onReopen = { sheet = null; reopen.launch(arrayOf("*/*")) },
            onCancel = { viewModel.cancelExport(); sheet = null },
            onRetry = { viewModel.dismissSaveStatus(); saveCopy() },
            onDismiss = { if (state.saveStatus !is SaveStatus.Saving) { viewModel.dismissSaveStatus(); sheet = null } }
        )
        state.recentEdits.firstOrNull { it.id == removeId }?.let { edit ->
            AlertDialog(
                onDismissRequest = { removeId = null }, title = { Text("Remove this edit?") },
                text = { Text("${edit.title}\n\nYour original and saved copies stay in your gallery.") },
                confirmButton = { TextButton({ viewModel.removeRecentEdit(edit.id); removeId = null }) { Text("Remove edit") } },
                dismissButton = { TextButton({ removeId = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun WelcomeScreen(
    state: FilmLabUiState, foldable: FoldableLayoutState,
    onContinue: () -> Unit, onChoose: () -> Unit, onBrowse: () -> Unit,
    onOpenRecent: (String) -> Unit, onRemoveRecent: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Camera, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text("FilmLab", style = MaterialTheme.typography.titleLarge)
        }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() }) {
            val hinge = foldable.hingeBounds?.takeIf { foldable.isSeparating || foldable.isHalfOpened }
            val top = with(density) { ((hinge?.top ?: 0) - origin.y).toDp() }
            val left = with(density) { ((hinge?.left ?: 0) - origin.x).toDp() }
            val actions: @Composable (Modifier) -> Unit = {
                WelcomeActions(state, onContinue, onChoose, onBrowse, onOpenRecent, onRemoveRecent, it)
            }
            when {
                hinge != null && foldable.isHorizontalHinge && top > 0.dp && top < maxHeight -> Column(Modifier.fillMaxSize()) {
                    WelcomeArtwork(state, Modifier.fillMaxWidth().height(top))
                    Spacer(Modifier.height(with(density) { hinge.height().toDp() }))
                    actions(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                }
                hinge != null && foldable.isVerticalHinge && left > 0.dp && left < maxWidth -> Row(Modifier.fillMaxSize()) {
                    WelcomeArtwork(state, Modifier.fillMaxHeight().width(left))
                    Spacer(Modifier.width(with(density) { hinge.width().toDp() }))
                    actions(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
                maxWidth >= 720.dp -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    WelcomeArtwork(state, Modifier.weight(1f))
                    actions(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WelcomeArtwork(state, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp))
                    actions(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WelcomeArtwork(state: FilmLabUiState, modifier: Modifier) {
    val recent = state.filmFullBitmap ?: state.recentEdits.firstOrNull()?.thumbnail
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        if (recent != null) Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
            Image(recent.asImageBitmap(), "Your latest edit", Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Crop)
            Text(state.rawMetadata?.fileName ?: state.recentEdits.firstOrNull()?.title ?: "Your latest edit",
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .45f)).padding(12.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium)
        } else Box(
            Modifier.widthIn(max = 278.dp).fillMaxWidth().rotate(-4f).shadow(10.dp)
                .background(Color(0xFFFFFEFA)).padding(9.dp, 9.dp, 9.dp, 24.dp)
        ) {
            Image(painterResource(R.drawable.welcome_photo), "Espresso in a terracotta cup on a café table",
                Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun WelcomeActions(
    state: FilmLabUiState, onContinue: () -> Unit, onChoose: () -> Unit, onBrowse: () -> Unit,
    onOpenRecent: (String) -> Unit, onRemoveRecent: (String) -> Unit, modifier: Modifier
) {
    val hasDraft = state.baseBitmap != null
    Column(modifier.padding(horizontal = 30.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (hasDraft || state.recentEdits.isNotEmpty()) "Your photos." else "Make it feel\nlike film.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Choose a photo.\nFind your look.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = if (hasDraft) onContinue else onChoose,
                shape = RoundedCornerShape(16.dp), colors = actionButtonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag(if (hasDraft) "continue_editing_button" else "choose_photo_button")
            ) {
                if (!hasDraft) {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (hasDraft) "Continue editing" else "Choose photo", style = MaterialTheme.typography.titleSmall)
            }
            if (hasDraft) TextButton(onChoose, Modifier.heightIn(min = 48.dp).testTag("choose_photo_button")) {
                Text("Choose another photo")
            }
            TextButton(onBrowse, Modifier.heightIn(min = 48.dp).testTag("browse_files_button")) { Text("Browse files") }
        }
        RecentEditsList(state.recentEdits, onOpenRecent, onRemoveRecent)
        Row(
            Modifier.padding(top = 22.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your original stays untouched.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OpeningPhoto() {
    Column(
        Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite }.testTag("opening_photo"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(20.dp))
        Text("Opening photo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EditorToolbar(
    saving: Boolean, canSave: Boolean, canUndo: Boolean, canRedo: Boolean,
    onBack: () -> Unit, onSave: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showTitle = maxWidth >= 390.dp && LocalDensity.current.fontScale < 1.4f
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to opening screen") }
            if (showTitle) Text("FilmLab", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            else Spacer(Modifier.weight(1f))
            IconButton(onUndo, Modifier.size(48.dp).testTag("undo_button"), enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo edit") }
            IconButton(onRedo, Modifier.size(48.dp).testTag("redo_button"), enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo edit") }
            Button(onSave, enabled = canSave && !saving, colors = actionButtonColors(),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("save_button")) {
                Text(if (saving) "Saving…" else "Save")
            }
        }
    }
}

@Composable
private fun EditorLayout(
    state: FilmLabUiState,
    foldable: FoldableLayoutState,
    zoom: ZoomPanState,
    onPreset: (FilmPreset) -> Unit,
    onStrength: (Float) -> Unit,
    onStrengthStart: () -> Unit,
    onStrengthEnd: () -> Unit,
    onCompare: (Boolean) -> Unit,
    onAdjust: () -> Unit,
    onLooks: () -> Unit,
    onCrop: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val hinge = foldable.hingeBounds?.takeIf { foldable.isSeparating || foldable.isHalfOpened }
        val hingeTop = with(density) { ((hinge?.top ?: 0) - origin.y).toDp() }
        val hingeLeft = with(density) { ((hinge?.left ?: 0) - origin.x).toDp() }
        val horizontalFold = hinge != null && foldable.isHorizontalHinge && hingeTop > 0.dp && hingeTop < maxHeight
        val verticalFold = hinge != null && foldable.isVerticalHinge && hingeLeft > 0.dp && hingeLeft < maxWidth
        val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
            FilmControls(state, onPreset, onStrength, onStrengthStart, onStrengthEnd, onCompare, onAdjust, onLooks, onCrop, onMore, controlsModifier)
        }
        when {
            horizontalFold -> Column(Modifier.fillMaxSize()) {
                PhotoCanvas(state, zoom, onCompare, Modifier.fillMaxWidth().height(hingeTop))
                Spacer(Modifier.height(with(density) { hinge!!.height().toDp() }))
                controls(Modifier.weight(1f).fillMaxWidth())
            }
            verticalFold -> Row(Modifier.fillMaxSize()) {
                PhotoCanvas(state, zoom, onCompare, Modifier.fillMaxHeight().width(hingeLeft))
                Spacer(Modifier.width(with(density) { hinge!!.width().toDp() }))
                controls(Modifier.weight(1f).fillMaxHeight())
            }
            maxWidth >= 720.dp || maxWidth > maxHeight * 1.3f -> Row(Modifier.fillMaxSize()) {
                PhotoCanvas(state, zoom, onCompare, Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width((availableWidth * .38f).coerceIn(280.dp, 400.dp)).fillMaxHeight())
            }
            else -> Column(Modifier.fillMaxSize()) {
                PhotoCanvas(state, zoom, onCompare, Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth().heightIn(max = availableHeight * .55f))
            }
        }
    }
}

@Composable
private fun PhotoCanvas(state: FilmLabUiState, zoom: ZoomPanState, onCompare: (Boolean) -> Unit, modifier: Modifier) {
    val base = (if (state.isComparing) state.comparisonBitmap else state.filmFullBitmap) ?: state.baseBitmap ?: return
    val scope = rememberCoroutineScope()
    val currentCompare by rememberUpdatedState(state.isComparing)
    val hasEdit by rememberUpdatedState(state.editSettings != com.example.film.EditSettings())
    val compare by rememberUpdatedState(onCompare)
    val holdDelay = LocalViewConfiguration.current.longPressTimeoutMillis
    BoxWithConstraints(modifier.background(Color(FilmEngine.PHOTO_MATTE_COLOR)).clipToBounds(), contentAlignment = Alignment.Center) {
        val aspect = base.width.toFloat() / base.height
        val fitted = if (maxWidth / maxHeight >= aspect) Modifier.fillMaxHeight().aspectRatio(aspect)
        else Modifier.fillMaxWidth().aspectRatio(aspect)
        Box(
            fitted.clipToBounds().onSizeChanged { zoom.updateContainerSize(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(zoom) { detectTransformGestures { centroid, pan, scale, _ -> zoom.onTransform(centroid, pan, scale) } }
                .pointerInput(zoom, state.baseBitmap, state.crop, state.selectedPreset) {
                    detectTapGestures(
                        onDoubleTap = { zoom.onDoubleTap(it, scope) },
                        onPress = {
                            coroutineScope {
                                var held = false
                                val hold = launch {
                                    delay(holdDelay)
                                    if (!zoom.isZoomed && !currentCompare && hasEdit) {
                                        held = true
                                        compare(true)
                                    }
                                }
                                try { tryAwaitRelease() } finally {
                                    hold.cancel()
                                    if (held) compare(false)
                                }
                            }
                        }
                    )
                }
                .semantics {
                    contentDescription = "Your photograph"
                    stateDescription = if (state.isComparing) "Original" else state.selectedPreset.filmName()
                    customActions = listOf(
                        CustomAccessibilityAction("Zoom in") { zoom.setZoomPreset((zoom.scale * 2).coerceAtMost(8f), scope); true },
                        CustomAccessibilityAction("Fit photo") { zoom.reset(scope); true }
                    )
                }.testTag("viewport_image_box")
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offsetX
                translationY = zoom.offsetY
            }) {
                Image(base.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        if (state.isComparing) Text(
            "Original", Modifier.align(Alignment.BottomCenter).padding(12.dp)
                .background(Color.Black.copy(alpha = .65f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium
        )
        if (zoom.isZoomed) TextButton(
            { zoom.reset(scope) },
            Modifier.align(Alignment.BottomEnd).padding(8.dp).heightIn(min = 48.dp).testTag("fit_photo_button"),
            colors = ButtonDefaults.textButtonColors(containerColor = Color.Black.copy(alpha = .65f), contentColor = Color.White)
        ) { Text("Fit") }
        if (state.isProcessing) Row(
            Modifier.align(Alignment.TopCenter).padding(12.dp).background(Color.Black.copy(alpha = .65f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            Text("Updating photo…", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal val filmOrder: List<FilmPreset> get() = listOf(
    FilmPreset.ORIGINAL, FilmPreset.PORTRA_400, FilmPreset.GOLD_200, FilmPreset.ILFORD_DELTA_100,
    FilmPreset.KODACHROME_64, FilmPreset.FUJI_VELVIA_50, FilmPreset.CINESTILL_800T,
    FilmPreset.FUJI_PRO_400H, FilmPreset.ILFORD_HP5, FilmPreset.KODAK_TRI_X, FilmPreset.EKTACHROME_E100
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilmControls(
    state: FilmLabUiState,
    onPreset: (FilmPreset) -> Unit,
    onStrength: (Float) -> Unit,
    onStrengthStart: () -> Unit,
    onStrengthEnd: () -> Unit,
    onCompare: (Boolean) -> Unit,
    onAdjust: () -> Unit,
    onLooks: () -> Unit,
    onCrop: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.selectedPreset.filmName(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onLooks, Modifier.heightIn(min = 48.dp).testTag("all_looks_button")) { Text("All looks") }
            }
            Text(state.selectedPreset.filmDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((listOf(FilmPreset.ORIGINAL) + state.favouritePresets + state.selectedPreset + listOf(FilmPreset.PORTRA_400, FilmPreset.GOLD_200, FilmPreset.ILFORD_DELTA_100)).distinct()) { preset ->
                FilmThumbnail(preset, state.presetPreviews[preset], state.selectedPreset == preset) { onPreset(preset) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Strength", style = MaterialTheme.typography.bodyMedium)
                Text("${(state.intensity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                state.intensity, { onStrengthStart(); onStrength(it) }, onValueChangeFinished = onStrengthEnd,
                enabled = state.selectedPreset != FilmPreset.ORIGINAL,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Film strength" }.testTag("intensity_slider")
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            EditorAction(
                "Compare", Icons.Default.Compare, { onCompare(!state.isComparing) }, Modifier.testTag("compare_button"),
                enabled = state.baseBitmap != null,
                selected = state.isComparing,
                stateLabel = if (state.isComparing) "Showing original" else "Showing edit"
            )
            EditorAction("Adjust", Icons.Default.Tune, onAdjust, Modifier.testTag("adjust_button"))
            EditorAction("Crop", Icons.Default.Crop, onCrop, Modifier.testTag("crop_button"))
            IconButton(onMore, Modifier.size(48.dp).testTag("more_button")) { Icon(Icons.Default.MoreHoriz, "More photo options") }
        }
    }
}

@Composable
internal fun FilmThumbnail(preset: FilmPreset, bitmap: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clip(RoundedCornerShape(14.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = preset.filmName() }.testTag("preset_${preset.shortCode.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(76.dp).border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(14.dp)).padding(5.dp)
                .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            if (selected) Icon(
                Icons.Default.Check, null,
                Modifier.align(Alignment.BottomEnd).padding(3.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(2.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        Text(
            preset.filmName(), Modifier.padding(horizontal = 2.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EditorAction(
    label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier,
    enabled: Boolean = true, selected: Boolean = false, stateLabel: String? = null
) {
    TextButton(
        onClick, modifier.heightIn(min = 48.dp).semantics { stateLabel?.let { stateDescription = it } }, enabled,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
internal fun SheetHeader(title: String, onDismiss: () -> Unit, actionLabel: String = "Done") {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
    }
}

@Composable
internal fun actionButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
)

internal fun FilmPreset.filmName(): String = when (this) {
    FilmPreset.ORIGINAL -> "Original"
    FilmPreset.PORTRA_400 -> "Portra 400"
    FilmPreset.KODACHROME_64 -> "Kodachrome 64"
    FilmPreset.FUJI_VELVIA_50 -> "Velvia 50"
    FilmPreset.CINESTILL_800T -> "CineStill 800T"
    FilmPreset.FUJI_PRO_400H -> "Pro 400H"
    FilmPreset.ILFORD_HP5 -> "Ilford HP5"
    FilmPreset.KODAK_TRI_X -> "Tri-X 400"
    FilmPreset.GOLD_200 -> "Gold 200"
    FilmPreset.ILFORD_DELTA_100 -> "Delta 100"
    FilmPreset.EKTACHROME_E100 -> "Ektachrome E100"
}

internal fun FilmPreset.filmDescription(): String = when (this) {
    FilmPreset.ORIGINAL -> "Your photo, without a film look"
    FilmPreset.PORTRA_400 -> "Warm, soft colour"
    FilmPreset.KODACHROME_64 -> "Rich, nostalgic colour"
    FilmPreset.FUJI_VELVIA_50 -> "Vivid and crisp"
    FilmPreset.CINESTILL_800T -> "Cool, cinematic colour"
    FilmPreset.FUJI_PRO_400H -> "Light, pastel colour"
    FilmPreset.ILFORD_HP5 -> "Soft black and white"
    FilmPreset.KODAK_TRI_X -> "Bold black and white"
    FilmPreset.GOLD_200 -> "Warm, everyday colour"
    FilmPreset.ILFORD_DELTA_100 -> "Clean, fine-grain black and white"
    FilmPreset.EKTACHROME_E100 -> "Neutral, refined slide colour"
}
