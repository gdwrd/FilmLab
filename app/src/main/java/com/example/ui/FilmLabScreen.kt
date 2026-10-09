package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.film.FilmPreset
import com.example.ui.theme.DarkroomBlack
import com.example.ui.theme.DarkroomSurface
import com.example.ui.theme.DarkroomSurfaceBorder
import com.example.ui.theme.DarkroomSurfaceElevated
import com.example.ui.theme.DarkroomTextMuted
import com.example.ui.theme.DarkroomTextPrimary
import com.example.ui.theme.DarkroomTextSecondary
import com.example.ui.theme.KodachromeAmber

@Composable
fun FilmLabScreen(
    viewModel: FilmLabViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val foldableLayout = rememberFoldableLayoutState()

    // Modern zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onImagePicked(uri)
        }
    }

    val hasPhoto = uiState.baseBitmap != null

    // Handle save status notifications
    LaunchedEffect(uiState.saveStatus) {
        when (val status = uiState.saveStatus) {
            is SaveStatus.Success -> {
                snackbarHostState.showSnackbar(status.message)
                viewModel.dismissSaveStatus()
            }
            is SaveStatus.Error -> {
                snackbarHostState.showSnackbar(status.error)
                viewModel.dismissSaveStatus()
            }
            else -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkroomBlack)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTabletop = foldableLayout.posture == FoldableDevicePosture.TABLETOP
            val isWideOrBook = foldableLayout.posture == FoldableDevicePosture.BOOK_MODE || maxWidth >= 600.dp

            when {
                // Tabletop Posture (Foldable half-opened horizontally on table)
                isTabletop -> {
                    FilmLabTabletopLayout(
                        uiState = uiState,
                        hasPhoto = hasPhoto,
                        foldableLayout = foldableLayout,
                        onInfoClick = { viewModel.toggleInfoSheet(true) },
                        onSaveClick = { viewModel.saveToGallery(context) },
                        onPresetSelected = { viewModel.selectPreset(it) },
                        onIntensityChange = { viewModel.setIntensity(it) },
                        onCompareStart = { viewModel.setComparing(true) },
                        onCompareEnd = { viewModel.setComparing(false) },
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onSelectSample = { sampleId -> viewModel.loadSample(sampleId) }
                    )
                }

                // Unfolded Wide Screen or Book Posture (Galaxy Z Fold / Pixel Fold inner display or book mode)
                isWideOrBook -> {
                    FilmLabDualPaneLayout(
                        uiState = uiState,
                        hasPhoto = hasPhoto,
                        foldableLayout = foldableLayout,
                        onInfoClick = { viewModel.toggleInfoSheet(true) },
                        onSaveClick = { viewModel.saveToGallery(context) },
                        onPresetSelected = { viewModel.selectPreset(it) },
                        onIntensityChange = { viewModel.setIntensity(it) },
                        onCompareStart = { viewModel.setComparing(true) },
                        onCompareEnd = { viewModel.setComparing(false) },
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onSelectSample = { sampleId -> viewModel.loadSample(sampleId) }
                    )
                }

                // Compact Portrait (Folded cover screen or standard phone)
                else -> {
                    FilmLabStandardLayout(
                        uiState = uiState,
                        hasPhoto = hasPhoto,
                        onInfoClick = { viewModel.toggleInfoSheet(true) },
                        onSaveClick = { viewModel.saveToGallery(context) },
                        onPresetSelected = { viewModel.selectPreset(it) },
                        onIntensityChange = { viewModel.setIntensity(it) },
                        onCompareStart = { viewModel.setComparing(true) },
                        onCompareEnd = { viewModel.setComparing(false) },
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onSelectSample = { sampleId -> viewModel.loadSample(sampleId) }
                    )
                }
            }
        }

        // Snackbar Host for export notifications
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        )

        // Film Calibration Info Dialog (opened by tapping app title)
        if (uiState.showInfoSheet) {
            FilmInfoDialog(
                onDismiss = { viewModel.toggleInfoSheet(false) }
            )
        }
    }
}

/**
 * Standard Compact Handheld Layout (Cover screen or vertical phone)
 */
@Composable
fun FilmLabStandardLayout(
    uiState: FilmLabUiState,
    hasPhoto: Boolean,
    onInfoClick: () -> Unit,
    onSaveClick: () -> Unit,
    onPresetSelected: (FilmPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onCompareStart: () -> Unit,
    onCompareEnd: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectSample: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        FilmLabTopBar(
            hasPhoto = hasPhoto,
            postureBadge = null,
            onInfoClick = onInfoClick,
            onSaveClick = onSaveClick,
            isSaving = uiState.saveStatus is SaveStatus.Saving
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            FilmViewport(
                baseBitmap = uiState.baseBitmap,
                filmBitmap = uiState.filmFullBitmap,
                intensity = uiState.intensity,
                isComparing = uiState.isComparing,
                isProcessing = uiState.isProcessing,
                activePreset = uiState.selectedPreset,
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                onCompareStart = onCompareStart,
                onCompareEnd = onCompareEnd,
                onPickPhoto = onPickPhoto,
                onSelectSample = onSelectSample
            )
        }

        FilmControlDock(
            hasPhoto = hasPhoto,
            selectedPreset = uiState.selectedPreset,
            intensity = uiState.intensity,
            onPresetSelected = onPresetSelected,
            onIntensityChange = onIntensityChange,
            onPickPhoto = onPickPhoto
        )
    }
}

/**
 * Tabletop Foldable Layout (Upper half = Viewport Monitor, Lower half = Desk Console)
 */
@Composable
fun FilmLabTabletopLayout(
    uiState: FilmLabUiState,
    hasPhoto: Boolean,
    foldableLayout: FoldableLayoutState,
    onInfoClick: () -> Unit,
    onSaveClick: () -> Unit,
    onPresetSelected: (FilmPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onCompareStart: () -> Unit,
    onCompareEnd: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectSample: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Upper Display: Hands-free Darkroom Monitor
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            FilmLabTopBar(
                hasPhoto = hasPhoto,
                postureBadge = "TABLETOP MONITOR",
                postureIcon = Icons.Default.Laptop,
                onInfoClick = onInfoClick,
                onSaveClick = onSaveClick,
                isSaving = uiState.saveStatus is SaveStatus.Saving
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                FilmViewport(
                    baseBitmap = uiState.baseBitmap,
                    filmBitmap = uiState.filmFullBitmap,
                    intensity = uiState.intensity,
                    isComparing = uiState.isComparing,
                    isProcessing = uiState.isProcessing,
                    activePreset = uiState.selectedPreset,
                    imageWidth = uiState.imageWidth,
                    imageHeight = uiState.imageHeight,
                    onCompareStart = onCompareStart,
                    onCompareEnd = onCompareEnd,
                    onPickPhoto = onPickPhoto,
                    onSelectSample = onSelectSample
                )
            }
        }

        // Physical Hinge Separator Indicator
        Surface(
            color = DarkroomBlack,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (foldableLayout.isSeparating) 14.dp else 8.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.25f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(uiState.selectedPreset.accentColor.copy(alpha = 0.5f))
                )
            }
        }

        // Lower Display: Flat Tactile Desk Console
        Surface(
            color = DarkroomSurface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Tabletop Console Status Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(uiState.selectedPreset.accentColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "DARKROOM CONSOLE",
                            color = DarkroomTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }

                    Text(
                        text = "${(uiState.intensity * 100).toInt()}% · ${uiState.selectedPreset.displayName}",
                        color = uiState.selectedPreset.accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Slider(
                    value = uiState.intensity,
                    onValueChange = onIntensityChange,
                    valueRange = 0f..1f,
                    enabled = hasPhoto,
                    colors = SliderDefaults.colors(
                        thumbColor = uiState.selectedPreset.accentColor,
                        activeTrackColor = uiState.selectedPreset.accentColor,
                        inactiveTrackColor = DarkroomSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("intensity_slider")
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Presets Carousel
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FilmPreset.values()) { preset ->
                        FilmPresetCard(
                            preset = preset,
                            isSelected = uiState.selectedPreset == preset,
                            onClick = { onPresetSelected(preset) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Actions & Quick Pickers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPickPhoto,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkroomSurfaceElevated,
                            contentColor = DarkroomTextPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("upload_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = uiState.selectedPreset.accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Choose Photo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onSaveClick,
                        enabled = hasPhoto && uiState.saveStatus !is SaveStatus.Saving,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KodachromeAmber,
                            contentColor = DarkroomBlack
                        ),
                        modifier = Modifier
                            .weight(0.85f)
                            .testTag("save_button")
                    ) {
                        Icon(imageVector = Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Dual-Pane Canonical Layout for Unfolded Inner Display & Book Posture
 */
@Composable
fun FilmLabDualPaneLayout(
    uiState: FilmLabUiState,
    hasPhoto: Boolean,
    foldableLayout: FoldableLayoutState,
    onInfoClick: () -> Unit,
    onSaveClick: () -> Unit,
    onPresetSelected: (FilmPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onCompareStart: () -> Unit,
    onCompareEnd: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectSample: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Left Pane: Expansive 35mm Photo Viewport
        Column(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            FilmLabTopBar(
                hasPhoto = hasPhoto,
                postureBadge = if (foldableLayout.posture == FoldableDevicePosture.BOOK_MODE) "BOOK POSTURE" else "UNFOLDED 35mm LAB",
                postureIcon = if (foldableLayout.posture == FoldableDevicePosture.BOOK_MODE) Icons.AutoMirrored.Filled.MenuBook else null,
                onInfoClick = onInfoClick,
                onSaveClick = onSaveClick,
                isSaving = uiState.saveStatus is SaveStatus.Saving
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                FilmViewport(
                    baseBitmap = uiState.baseBitmap,
                    filmBitmap = uiState.filmFullBitmap,
                    intensity = uiState.intensity,
                    isComparing = uiState.isComparing,
                    isProcessing = uiState.isProcessing,
                    activePreset = uiState.selectedPreset,
                    imageWidth = uiState.imageWidth,
                    imageHeight = uiState.imageHeight,
                    onCompareStart = onCompareStart,
                    onCompareEnd = onCompareEnd,
                    onPickPhoto = onPickPhoto,
                    onSelectSample = onSelectSample
                )
            }
        }

        // Hinge / Vertical Divider
        Box(
            modifier = Modifier
                .width(if (foldableLayout.isSeparating) 12.dp else 1.dp)
                .fillMaxHeight()
                .background(DarkroomSurfaceBorder)
        )

        // Right Pane: Dedicated Darkroom Control Console
        Surface(
            color = DarkroomSurface,
            modifier = Modifier
                .weight(0.85f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header & Action Bar (Save button only, NO share button)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DARKROOM CONSOLE",
                            color = DarkroomTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "${uiState.selectedPreset.category} · ${uiState.selectedPreset.isoRating}",
                            color = uiState.selectedPreset.accentColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Button(
                        onClick = onSaveClick,
                        enabled = hasPhoto && uiState.saveStatus !is SaveStatus.Saving,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KodachromeAmber,
                            contentColor = DarkroomBlack
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("save_button")
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Emulsion Intensity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EMULSION INTENSITY",
                        color = DarkroomTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${(uiState.intensity * 100).toInt()}%",
                        color = uiState.selectedPreset.accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = uiState.intensity,
                    onValueChange = onIntensityChange,
                    valueRange = 0f..1f,
                    enabled = hasPhoto,
                    colors = SliderDefaults.colors(
                        thumbColor = uiState.selectedPreset.accentColor,
                        activeTrackColor = uiState.selectedPreset.accentColor,
                        inactiveTrackColor = DarkroomSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("intensity_slider")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Film Stock Presets Selector
                Text(
                    text = "CALIBRATED FILM STOCKS (8 STOCKS)",
                    color = DarkroomTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Presets horizontal carousel
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FilmPreset.values()) { preset ->
                        FilmPresetCard(
                            preset = preset,
                            isSelected = uiState.selectedPreset == preset,
                            onClick = { onPresetSelected(preset) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Active Preset Chemistry & Sensitometry Card
                Surface(
                    color = DarkroomSurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, uiState.selectedPreset.borderColor.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.selectedPreset.displayName.uppercase(),
                                color = uiState.selectedPreset.accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = uiState.selectedPreset.isoRating,
                                color = DarkroomTextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.selectedPreset.chemistryInfo,
                            color = DarkroomTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Grain: ${uiState.selectedPreset.grainDescription}",
                            color = DarkroomTextMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Choose Photo Button
                Button(
                    onClick = onPickPhoto,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkroomSurfaceElevated,
                        contentColor = DarkroomTextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("upload_photo_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = uiState.selectedPreset.accentColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (hasPhoto) "Choose Another Photo" else "Choose Photo from Gallery", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Fast test scenes
                Text(
                    text = "QUICK TEST SCENES",
                    color = DarkroomTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SampleSceneChip(title = "Coastal", subtitle = "Golden Dusk", onClick = { onSelectSample("golden_coastal") })
                    SampleSceneChip(title = "Shadows", subtitle = "Urban B&W", onClick = { onSelectSample("street_portrait") })
                    SampleSceneChip(title = "Cafe", subtitle = "Neon Lights", onClick = { onSelectSample("vintage_cafe") })
                }
            }
        }
    }
}

/**
 * Top bar with app branding, calibration info trigger, and Save button only (NO share button).
 */
@Composable
fun FilmLabTopBar(
    hasPhoto: Boolean,
    postureBadge: String? = null,
    postureIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onInfoClick: () -> Unit,
    onSaveClick: () -> Unit,
    isSaving: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App Title & Analog Scan Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onInfoClick() }
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .testTag("app_title_info_trigger")
        ) {
            Text(
                text = "FILMLAB",
                color = DarkroomTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = DarkroomSurfaceElevated,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder)
            ) {
                Text(
                    text = "35mm · 2048px",
                    color = KodachromeAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Foldable posture indicator badge
            if (postureBadge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = DarkroomSurfaceElevated,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, KodachromeAmber.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (postureIcon != null) {
                            Icon(postureIcon, contentDescription = null, tint = KodachromeAmber, modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = postureBadge,
                            color = KodachromeAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Right Action: Save to Gallery Button ONLY (No Share Button)
        Button(
            onClick = onSaveClick,
            enabled = hasPhoto && !isSaving,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = KodachromeAmber,
                contentColor = DarkroomBlack,
                disabledContainerColor = DarkroomSurfaceElevated,
                disabledContentColor = DarkroomTextMuted
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier
                .height(34.dp)
                .testTag("save_button")
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = DarkroomTextPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 35mm Viewport with responsive constraint-aware fitting.
 * Perfectly fits both HORIZONTAL and VERTICAL / PORTRAIT photos within the available container
 * bounds without ever overflowing or overlapping the top bar or bottom dock!
 */
@Composable
fun FilmViewport(
    baseBitmap: Bitmap?,
    filmBitmap: Bitmap?,
    intensity: Float,
    isComparing: Boolean,
    isProcessing: Boolean,
    activePreset: FilmPreset,
    imageWidth: Int,
    imageHeight: Int,
    onCompareStart: () -> Unit,
    onCompareEnd: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectSample: (String) -> Unit
) {
    if (baseBitmap == null) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight - 8.dp)
                    .border(1.dp, DarkroomSurfaceBorder, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("empty_viewport"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkroomSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        color = DarkroomSurfaceElevated,
                        shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                        modifier = Modifier
                            .size(54.dp)
                            .clickable { onPickPhoto() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Camera",
                                tint = KodachromeAmber,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "NO PHOTO LOADED",
                        color = DarkroomTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Pick from gallery or load a calibrated test scene:",
                        color = DarkroomTextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        SampleSceneChip(title = "Coastal Dusk", subtitle = "Golden Hour & Sky", onClick = { onSelectSample("golden_coastal") })
                        SampleSceneChip(title = "Street Shadows", subtitle = "High-Contrast B&W", onClick = { onSelectSample("street_portrait") })
                        SampleSceneChip(title = "Vintage Cafe", subtitle = "Neon & Halation", onClick = { onSelectSample("vintage_cafe") })
                    }
                }
            }
        }
        return
    }

    // Dynamic, constraint-aware calculation for flawless portrait & landscape fitting
    val safeWidth = imageWidth.toFloat().coerceAtLeast(1f)
    val safeHeight = imageHeight.toFloat().coerceAtLeast(1f)
    val photoAspect = (safeWidth / safeHeight).coerceIn(0.30f, 3.2f)

    val coroutineScope = rememberCoroutineScope()
    val zoomState = rememberZoomPanState()

    // Reset zoom when switching to a different photo
    LaunchedEffect(baseBitmap) {
        zoomState.resetImmediate()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth.value
        val containerHeight = maxHeight.value.coerceAtLeast(1f)
        val containerAspect = containerWidth / containerHeight

        // When the photo is wider than the container, fit to width (height fits within container bounds).
        // When the photo is taller (e.g. VERTICAL / PORTRAIT photos), fit to height (width fits within bounds).
        // This guarantees the card NEVER overflows or overlaps with adjacent UI elements!
        val cardModifier = if (photoAspect >= containerAspect) {
            Modifier
                .fillMaxWidth()
                .aspectRatio(photoAspect)
        } else {
            Modifier
                .fillMaxHeight()
                .aspectRatio(photoAspect)
        }

        Card(
            modifier = cardModifier
                .border(1.dp, DarkroomSurfaceBorder, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkroomSurface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clipToBounds()
                    .onSizeChanged { size ->
                        zoomState.updateContainerSize(size.width.toFloat(), size.height.toFloat())
                    }
                    .pointerInput(zoomState) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            zoomState.onTransform(centroid, pan, zoom)
                        }
                    }
                    .pointerInput(zoomState) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                zoomState.onDoubleTap(tapOffset, coroutineScope)
                            },
                            onPress = {
                                if (!zoomState.isZoomed) {
                                    onCompareStart()
                                    tryAwaitRelease()
                                    onCompareEnd()
                                } else {
                                    tryAwaitRelease()
                                }
                            }
                        )
                    }
                    .testTag("viewport_image_box"),
                contentAlignment = Alignment.Center
            ) {
                // Scaled & panned 35mm photo emulsion layers
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomState.scale
                            scaleY = zoomState.scale
                            translationX = zoomState.offsetX
                            translationY = zoomState.offsetY
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = baseBitmap.asImageBitmap(),
                        contentDescription = "Base 35mm scan",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    if (filmBitmap != null) {
                        val filmAlpha = if (isComparing) 0f else intensity
                        Image(
                            bitmap = filmBitmap.asImageBitmap(),
                            contentDescription = "Film simulated emulsion",
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(filmAlpha),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                if (isProcessing) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = activePreset.accentColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Developing Emulsion...",
                                color = DarkroomTextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Preset Pill Badge (Top-End)
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, activePreset.borderColor.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(activePreset.accentColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isComparing) "ORIGINAL (RAW)" else activePreset.displayName.uppercase(),
                            color = if (isComparing) DarkroomTextSecondary else activePreset.accentColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Zoom Loupe HUD & Preset Buttons (Top-Start)
                Surface(
                    color = Color.Black.copy(alpha = 0.80f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (zoomState.isZoomed) KodachromeAmber.copy(alpha = 0.85f) else DarkroomSurfaceBorder
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .testTag("zoom_hud_bar")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom Loupe",
                            tint = if (zoomState.isZoomed) KodachromeAmber else DarkroomTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )

                        Text(
                            text = zoomState.zoomLabel,
                            color = if (zoomState.isZoomed) KodachromeAmber else DarkroomTextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("zoom_level_badge")
                        )

                        // 1x Fit Preset Button
                        Surface(
                            color = if (!zoomState.isZoomed) KodachromeAmber.copy(alpha = 0.25f) else DarkroomSurfaceElevated,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                if (!zoomState.isZoomed) KodachromeAmber else DarkroomSurfaceBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { zoomState.setZoomPreset(1.0f, coroutineScope) }
                                .testTag("zoom_1x_button")
                        ) {
                            Text(
                                text = "1×",
                                color = if (!zoomState.isZoomed) KodachromeAmber else DarkroomTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        // 2.5x Grain Loupe Preset Button
                        Surface(
                            color = if (zoomState.scale in 2.2f..2.8f) KodachromeAmber.copy(alpha = 0.25f) else DarkroomSurfaceElevated,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                if (zoomState.scale in 2.2f..2.8f) KodachromeAmber else DarkroomSurfaceBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { zoomState.setZoomPreset(2.5f, coroutineScope) }
                                .testTag("zoom_2_5x_button")
                        ) {
                            Text(
                                text = "2.5×",
                                color = if (zoomState.scale in 2.2f..2.8f) KodachromeAmber else DarkroomTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        // 5x Halide Detail Preset Button
                        Surface(
                            color = if (zoomState.scale in 4.5f..5.5f) KodachromeAmber.copy(alpha = 0.25f) else DarkroomSurfaceElevated,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                if (zoomState.scale in 4.5f..5.5f) KodachromeAmber else DarkroomSurfaceBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { zoomState.setZoomPreset(5.0f, coroutineScope) }
                                .testTag("zoom_5x_button")
                        ) {
                            Text(
                                text = "5×",
                                color = if (zoomState.scale in 4.5f..5.5f) KodachromeAmber else DarkroomTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        // Reset button appears whenever zoomed in
                        if (zoomState.isZoomed) {
                            Surface(
                                color = KodachromeAmber,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { zoomState.reset(coroutineScope) }
                                    .testTag("zoom_reset_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RestartAlt,
                                        contentDescription = "Reset Zoom",
                                        tint = DarkroomBlack,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "FIT",
                                        color = DarkroomBlack,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // Hold to Compare Pill (Bottom-End)
                Surface(
                    color = if (isComparing) KodachromeAmber else Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isComparing) KodachromeAmber else Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onCompareStart()
                                    tryAwaitRelease()
                                    onCompareEnd()
                                }
                            )
                        }
                        .testTag("hold_compare_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Compare,
                            contentDescription = null,
                            tint = if (isComparing) DarkroomBlack else DarkroomTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isComparing) "SHOWING ORIGINAL" else "HOLD TO COMPARE",
                            color = if (isComparing) DarkroomBlack else DarkroomTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Calibrated 35mm grain resolution stamp (Bottom-Start)
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (zoomState.isZoomed) {
                            "${imageWidth}×${imageHeight}px · ${(zoomState.scale * 100).toInt()}% Mag"
                        } else {
                            "${imageWidth}×${imageHeight}px · 35mm Scan"
                        },
                        color = if (zoomState.isZoomed) KodachromeAmber else DarkroomTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SampleSceneChip(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        color = DarkroomSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = KodachromeAmber,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = title,
                    color = DarkroomTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = DarkroomTextMuted,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
fun FilmControlDock(
    hasPhoto: Boolean,
    selectedPreset: FilmPreset,
    intensity: Float,
    onPresetSelected: (FilmPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onPickPhoto: () -> Unit
) {
    Surface(
        color = DarkroomSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EMULSION INTENSITY",
                        color = DarkroomTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${selectedPreset.category} · ${selectedPreset.isoRating}",
                        color = selectedPreset.accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Surface(
                    color = DarkroomSurfaceElevated,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder)
                ) {
                    Text(
                        text = "${(intensity * 100).toInt()}%",
                        color = if (intensity > 0f) selectedPreset.accentColor else DarkroomTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Slider(
                value = intensity,
                onValueChange = onIntensityChange,
                valueRange = 0f..1f,
                enabled = hasPhoto,
                colors = SliderDefaults.colors(
                    thumbColor = selectedPreset.accentColor,
                    activeTrackColor = selectedPreset.accentColor,
                    inactiveTrackColor = DarkroomSurfaceBorder,
                    disabledThumbColor = DarkroomSurfaceBorder,
                    disabledActiveTrackColor = DarkroomSurfaceBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("intensity_slider")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FILM STOCK SIMULATION (8 STOCKS)",
                    color = DarkroomTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )

                Text(
                    text = selectedPreset.displayName,
                    color = selectedPreset.accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(FilmPreset.values()) { preset ->
                    FilmPresetCard(
                        preset = preset,
                        isSelected = selectedPreset == preset,
                        onClick = { onPresetSelected(preset) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onPickPhoto,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkroomSurfaceElevated,
                    contentColor = DarkroomTextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("upload_photo_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Upload Photo from Gallery",
                    tint = selectedPreset.accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasPhoto) "Choose Another Photo" else "Choose Photo from Gallery",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun FilmPresetCard(
    preset: FilmPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) preset.accentColor else preset.borderColor
    val containerBg = if (isSelected) preset.cardBgColor else preset.cardBgColor.copy(alpha = 0.5f)

    Card(
        modifier = Modifier
            .width(138.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .border(
                width = if (isSelected) 1.8.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .testTag("preset_${preset.shortCode.lowercase()}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.shortCode,
                    color = if (isSelected) preset.accentColor else DarkroomTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = preset.accentColor,
                        modifier = Modifier.size(13.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(preset.accentColor.copy(alpha = 0.6f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = preset.displayName,
                color = if (isSelected) DarkroomTextPrimary else DarkroomTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = preset.subtitle,
                color = if (isSelected) preset.accentColor.copy(alpha = 0.9f) else DarkroomTextMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                color = DarkroomBlack.copy(alpha = 0.45f),
                shape = RoundedCornerShape(3.dp)
            ) {
                Text(
                    text = "${preset.category.take(8)} · ${preset.isoRating}",
                    color = DarkroomTextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
fun FilmInfoDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkroomSurfaceElevated,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = KodachromeAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FilmLab Optics & Sensitometry",
                    color = DarkroomTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                InfoSection(
                    title = "Standard 35mm Scan Resolution (2048px)",
                    description = "Analog film grain is tied to the physical 36×24mm emulsion surface (~17.5µm per pixel). FilmLab standardizes photos to 2048px on the long edge so that silver halide crystals and dye cloud density remain optically authentic across any sensor resolution."
                )

                InfoSection(
                    title = "Foldable Phone & Adaptive Darkroom Layouts",
                    description = "FilmLab actively detects foldable device postures via Jetpack WindowManager. In Tabletop posture (half-opened on a desk), the upright screen acts as a dedicated hands-free Darkroom Monitor while the base becomes a tactile developer console. On unfolded large screens or in Book mode, FilmLab transitions to an expansive dual-pane workstation."
                )

                InfoSection(
                    title = "1. Kodachrome 64 (1974 - 2009)",
                    description = "Legendary color reversal slide film (K-14 process). Characterized by rich subtractive dye coupling where cyan absorbs red in blue skies for deep cobalt heavens, saturated vermilion warm tones, and 5500K daylight highlight ivory glow."
                )

                InfoSection(
                    title = "2. Kodak Portra 400 (C-41)",
                    description = "The gold standard for portraiture. Soft contrast with a lifted orange-mask toe (never crushed pure blacks), wide exposure latitude, gentle highlight rolloff, peachy melanin skin warmth, and ultra-fine T-GRAIN tabular crystals."
                )

                InfoSection(
                    title = "3. Fujifilm Velvia 50 (E-6 Chrome)",
                    description = "Ultra-vivid landscape slide film with extreme gamma (1.68) and dense inky D-Max blacks. Calibrated with explosive emerald chlorophyll greens, deep navy slide skies, and microscopic ISO 50 dye clouds for razor-sharp micro-contrast."
                )

                InfoSection(
                    title = "4. CineStill 800T (Tungsten Vision3)",
                    description = "Eastman Kodak Vision3 500T motion picture film converted for C-41 by omitting the carbon Remjet layer. Features cool 3200K tungsten teal shadows and authentic optical red halation bloom scattering around specular highlights."
                )

                InfoSection(
                    title = "5. Fujifilm Pro 400H (C-41)",
                    description = "Iconic Japanese portrait negative featuring a 4th color-sensitive cyan layer. Neutralizes muddy shadow casts with an airy pastel mint/lavender undertone and renders creamy, porcelain skin tones."
                )

                InfoSection(
                    title = "6. Ilford HP5 Plus (400 ISO B&W)",
                    description = "Classic British panchromatic black-and-white negative. Pushed micro-contrast with optical yellow filter #8 tonal sky separation, deep D-Max blacks, and tactile cubic silver halide crystalline grain."
                )

                InfoSection(
                    title = "7. Kodak Tri-X 400 (D-76 Grit)",
                    description = "Quintessential photojournalism B&W (Henri Cartier-Bresson, Garry Winogrand). Harder contrast than HP5 with ortho-panchromatic blue attenuation, charcoal shadows, and multi-scale metallic silver filament clumps."
                )

                InfoSection(
                    title = "Real-Time Intensity & Hold-to-Compare",
                    description = "Use the slider to dial between subtle analog flavor and 100% full emulsion depth. Press and hold anywhere on the viewport to instantly compare the emulsion with the unfiltered digital original."
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KodachromeAmber,
                    contentColor = DarkroomBlack
                )
            ) {
                Text(
                    text = "Close",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
fun InfoSection(title: String, description: String) {
    Column {
        Text(
            text = title,
            color = KodachromeAmber,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            color = DarkroomTextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}
