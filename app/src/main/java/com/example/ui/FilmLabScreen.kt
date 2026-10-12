package com.example.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.alpha
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
import com.example.film.FilmPreset
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

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(viewModel::onImagePicked)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::onImagePicked)
    }
    val savePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.saveToGallery(context)
        else scope.launch { snackbar.showSnackbar("Allow storage access to save a copy on this Android version.") }
    }
    val saveCopy = {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) savePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.saveToGallery(context)
    }

    LaunchedEffect(state.baseBitmap) { zoom.resetImmediate() }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.saveStatus) {
        when (val result = state.saveStatus) {
            is SaveStatus.Success -> {
                if (snackbar.showSnackbar(result.message, actionLabel = "View") == SnackbarResult.ActionPerformed) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(result.uri, "image/jpeg")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: ActivityNotFoundException) {
                        snackbar.showSnackbar("Saved to gallery. No photo viewer is installed.")
                    } catch (_: SecurityException) {
                        snackbar.showSnackbar("Saved to gallery. Open your gallery to view the copy.")
                    }
                }
                viewModel.dismissSaveStatus()
            }
            is SaveStatus.Error -> {
                snackbar.showSnackbar(result.error)
                viewModel.dismissSaveStatus()
            }
            else -> Unit
        }
    }
    BackHandler(enabled = (state.isImporting || !state.showWelcome) && sheet == null) {
        viewModel.openWelcome()
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        when {
            state.isImporting -> OpeningPhoto()
            state.showWelcome || state.baseBitmap == null -> WelcomeScreen(
                hasDraft = state.baseBitmap != null,
                foldable = foldable,
                onContinue = viewModel::resumeEditing,
                onChoose = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onBrowse = { files.launch(arrayOf("*/*")) }
            )
            else -> Column(Modifier.fillMaxSize()) {
                EditorToolbar(
                    saving = state.saveStatus is SaveStatus.Saving,
                    canSave = !state.isProcessing && !state.isImporting,
                    onBack = viewModel::openWelcome,
                    onSave = saveCopy
                )
                EditorLayout(
                    state = state,
                    foldable = foldable,
                    zoom = zoom,
                    onPreset = { viewModel.setComparing(false); viewModel.selectPreset(it) },
                    onStrength = viewModel::setIntensity,
                    onCompare = viewModel::setComparing,
                    onAdjust = { viewModel.setComparing(false); sheet = "adjust" },
                    onInfo = { sheet = "info" },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    if (sheet == "adjust" && state.baseBitmap != null) {
        AdjustSheet(state, viewModel::setGrainIntensity, viewModel::resetAdjustments) { sheet = null }
    }
    if (sheet == "info" && state.baseBitmap != null) {
        PhotoInfoSheet(state) { sheet = null }
    }
}

@Composable
private fun WelcomeScreen(
    hasDraft: Boolean, foldable: FoldableLayoutState,
    onContinue: () -> Unit, onChoose: () -> Unit, onBrowse: () -> Unit
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
                WelcomeActions(hasDraft, onContinue, onChoose, onBrowse, it)
            }
            when {
                hinge != null && foldable.isHorizontalHinge && top > 0.dp && top < maxHeight -> Column(Modifier.fillMaxSize()) {
                    WelcomeArtwork(Modifier.fillMaxWidth().height(top))
                    Spacer(Modifier.height(with(density) { hinge.height().toDp() }))
                    actions(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                }
                hinge != null && foldable.isVerticalHinge && left > 0.dp && left < maxWidth -> Row(Modifier.fillMaxSize()) {
                    WelcomeArtwork(Modifier.fillMaxHeight().width(left))
                    Spacer(Modifier.width(with(density) { hinge.width().toDp() }))
                    actions(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
                maxWidth >= 720.dp -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    WelcomeArtwork(Modifier.weight(1f))
                    actions(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WelcomeArtwork(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp))
                    actions(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WelcomeArtwork(modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = 278.dp).fillMaxWidth().rotate(-4f).shadow(10.dp)
                .background(Color(0xFFFFFEFA)).padding(9.dp, 9.dp, 9.dp, 24.dp)
        ) {
            Image(
                painterResource(R.drawable.welcome_photo), "Espresso in a terracotta cup on a café table",
                Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun WelcomeActions(
    hasDraft: Boolean, onContinue: () -> Unit, onChoose: () -> Unit, onBrowse: () -> Unit, modifier: Modifier
) {
    Column(modifier.padding(horizontal = 30.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Make it feel\nlike film.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
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
private fun EditorToolbar(saving: Boolean, canSave: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showTitle = maxWidth >= 360.dp || LocalDensity.current.fontScale < 1.5f
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to opening screen") }
            if (showTitle) Text("FilmLab", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
            else Spacer(Modifier.weight(1f))
            Button(onSave, enabled = canSave && !saving, colors = actionButtonColors(), modifier = Modifier.heightIn(min = 48.dp).testTag("save_button")) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (saving) "Saving…" else "Save copy")
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
    onCompare: (Boolean) -> Unit,
    onAdjust: () -> Unit,
    onInfo: () -> Unit,
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
            FilmControls(state, onPreset, onStrength, onCompare, onAdjust, onInfo, controlsModifier)
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
    val base = state.baseBitmap ?: return
    val scope = rememberCoroutineScope()
    val currentCompare by rememberUpdatedState(state.isComparing)
    val compare by rememberUpdatedState(onCompare)
    val holdDelay = LocalViewConfiguration.current.longPressTimeoutMillis
    BoxWithConstraints(modifier.background(Color(0xFF111112)).clipToBounds(), contentAlignment = Alignment.Center) {
        val aspect = base.width.toFloat() / base.height
        val fitted = if (maxWidth / maxHeight >= aspect) Modifier.fillMaxHeight().aspectRatio(aspect)
        else Modifier.fillMaxWidth().aspectRatio(aspect)
        Box(
            fitted.clipToBounds().onSizeChanged { zoom.updateContainerSize(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(zoom) { detectTransformGestures { centroid, pan, scale, _ -> zoom.onTransform(centroid, pan, scale) } }
                .pointerInput(zoom, base, state.selectedPreset) {
                    detectTapGestures(
                        onDoubleTap = { zoom.onDoubleTap(it, scope) },
                        onPress = {
                            coroutineScope {
                                var held = false
                                val hold = launch {
                                    delay(holdDelay)
                                    if (!zoom.isZoomed && !currentCompare && state.selectedPreset != FilmPreset.ORIGINAL) {
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
                state.filmFullBitmap?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (state.isComparing) 0f else state.intensity), contentScale = ContentScale.Fit)
                }
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
            Text("Applying film…", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val filmOrder = listOf(
    FilmPreset.ORIGINAL, FilmPreset.PORTRA_400, FilmPreset.KODACHROME_64, FilmPreset.FUJI_VELVIA_50,
    FilmPreset.CINESTILL_800T, FilmPreset.FUJI_PRO_400H, FilmPreset.ILFORD_HP5, FilmPreset.KODAK_TRI_X
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilmControls(
    state: FilmLabUiState,
    onPreset: (FilmPreset) -> Unit,
    onStrength: (Float) -> Unit,
    onCompare: (Boolean) -> Unit,
    onAdjust: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(state.selectedPreset.filmName(), style = MaterialTheme.typography.titleMedium)
            Text(state.selectedPreset.filmDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filmOrder) { preset ->
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
                state.intensity, onStrength,
                enabled = state.selectedPreset != FilmPreset.ORIGINAL,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Film strength" }.testTag("intensity_slider")
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            EditorAction(
                "Compare", Icons.Default.Compare, { onCompare(!state.isComparing) }, Modifier.testTag("compare_button"),
                enabled = state.selectedPreset != FilmPreset.ORIGINAL,
                selected = state.isComparing,
                stateLabel = if (state.isComparing) "Showing original" else "Showing edit"
            )
            EditorAction("Adjust", Icons.Default.Tune, onAdjust, Modifier.testTag("adjust_button"))
            EditorAction("Info", Icons.Default.Info, onInfo, Modifier.testTag("photo_info_button"))
        }
    }
}

@Composable
private fun FilmThumbnail(preset: FilmPreset, bitmap: Bitmap?, selected: Boolean, onClick: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustSheet(state: FilmLabUiState, onGrain: (Float) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            SheetHeader("Adjust", onDismiss)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Grain")
                Text("${(state.grainIntensity * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                state.grainIntensity, onGrain, valueRange = 0f..2f,
                enabled = state.selectedPreset != FilmPreset.ORIGINAL,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Grain amount" }.testTag("grain_slider")
            )
            Text(
                if (state.selectedPreset == FilmPreset.ORIGINAL) "Choose a film look to add grain." else "A little texture. Or a little more.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onReset, Modifier.heightIn(min = 48.dp).testTag("reset_adjustments_button")) { Text("Reset adjustments") }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Done") }
    }
}

@Composable
private fun actionButtonColors() = ButtonDefaults.buttonColors(
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
}

internal fun FilmPreset.filmDescription(): String = when (this) {
    FilmPreset.ORIGINAL -> "Just as you took it"
    FilmPreset.PORTRA_400 -> "Warm, soft colour"
    FilmPreset.KODACHROME_64 -> "Rich, nostalgic colour"
    FilmPreset.FUJI_VELVIA_50 -> "Vivid and crisp"
    FilmPreset.CINESTILL_800T -> "Cool, cinematic colour"
    FilmPreset.FUJI_PRO_400H -> "Light, pastel colour"
    FilmPreset.ILFORD_HP5 -> "Soft black and white"
    FilmPreset.KODAK_TRI_X -> "Bold black and white"
}
