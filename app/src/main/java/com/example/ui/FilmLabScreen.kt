package com.example.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.film.FilmEngine
import com.example.film.FilmPreset
import com.example.ui.theme.DarkroomBlack
import com.example.ui.theme.DarkroomSurface
import com.example.ui.theme.DarkroomSurfaceBorder
import com.example.ui.theme.DarkroomSurfaceElevated
import com.example.ui.theme.DarkroomTextMuted
import com.example.ui.theme.DarkroomTextPrimary
import com.example.ui.theme.DarkroomTextSecondary
import com.example.ui.theme.IlfordCardBg
import com.example.ui.theme.IlfordSilver
import com.example.ui.theme.KodachromeAmber
import com.example.ui.theme.KodachromeCardBg
import com.example.ui.theme.SuccessGreen

@Composable
fun FilmLabScreen(
    viewModel: FilmLabViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Modern zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onImagePicked(uri)
        }
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Minimalist Top Bar
            FilmLabTopBar(
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                onInfoClick = { viewModel.toggleInfoSheet(true) },
                onShareClick = { viewModel.shareImage(context) },
                onSaveClick = { viewModel.saveToGallery(context) },
                isSaving = uiState.saveStatus is SaveStatus.Saving
            )

            // Center Viewport: Photo Preview & Hold-to-Compare
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    onCompareStart = { viewModel.setComparing(true) },
                    onCompareEnd = { viewModel.setComparing(false) }
                )
            }

            // Bottom Control Dock: Intensity slider, presets, photo picker
            FilmControlDock(
                selectedPreset = uiState.selectedPreset,
                intensity = uiState.intensity,
                activeSampleId = uiState.activeSampleId,
                onPresetSelected = { viewModel.selectPreset(it) },
                onIntensityChange = { viewModel.setIntensity(it) },
                onPickPhoto = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onSelectSample = { viewModel.loadSample(it) }
            )
        }

        // Snackbar Host for export notifications
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        )

        // Film Calibration Info Dialog
        if (uiState.showInfoSheet) {
            FilmInfoDialog(
                onDismiss = { viewModel.toggleInfoSheet(false) }
            )
        }
    }
}

@Composable
fun FilmLabTopBar(
    imageWidth: Int,
    imageHeight: Int,
    onInfoClick: () -> Unit,
    onShareClick: () -> Unit,
    onSaveClick: () -> Unit,
    isSaving: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App Title & Analog Scan Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onInfoClick() }
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
        }

        // Action Icons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("info_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Film Chemistry & Resolution Info",
                    tint = DarkroomTextSecondary
                )
            }

            IconButton(
                onClick = onShareClick,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("share_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share 35mm Scan",
                    tint = DarkroomTextSecondary
                )
            }

            // Save to Gallery Button
            Button(
                onClick = onSaveClick,
                enabled = !isSaving,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KodachromeAmber,
                    contentColor = DarkroomBlack,
                    disabledContainerColor = DarkroomSurfaceElevated,
                    disabledContentColor = DarkroomTextMuted
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("save_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = DarkroomTextPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Save",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

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
    onCompareEnd: () -> Unit
) {
    if (baseBitmap == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = KodachromeAmber)
        }
        return
    }

    val aspect = (imageWidth.toFloat() / imageHeight.toFloat()).coerceIn(0.5f, 2.5f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .border(1.dp, DarkroomSurfaceBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkroomSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onCompareStart()
                            tryAwaitRelease()
                            onCompareEnd()
                        }
                    )
                }
                .testTag("viewport_image_box"),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: Base Original Image (always present)
            Image(
                bitmap = baseBitmap.asImageBitmap(),
                contentDescription = "Base 35mm scan",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Layer 2: Film Filter with real-time hardware-accelerated intensity blending
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

            // Processing indicator overlay
            if (isProcessing) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
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
                            color = KodachromeAmber
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

            // Top-Right: Preset Pill Badge
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(6.dp),
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

            // Bottom-Right: Tactile "Hold to Compare" interactive hint pill
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

            // Bottom-Left: Calibrated 35mm grain resolution stamp
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = "${imageWidth}×${imageHeight}px · 35mm Scan",
                    color = DarkroomTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
fun FilmControlDock(
    selectedPreset: FilmPreset,
    intensity: Float,
    activeSampleId: String?,
    onPresetSelected: (FilmPreset) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onPickPhoto: () -> Unit,
    onSelectSample: (String) -> Unit
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Section 1: Intensity Slider with readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "EMULSION INTENSITY",
                        color = DarkroomTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    color = DarkroomSurfaceElevated,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder)
                ) {
                    Text(
                        text = "${(intensity * 100).toInt()}%",
                        color = if (intensity > 0f) KodachromeAmber else DarkroomTextSecondary,
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
                colors = SliderDefaults.colors(
                    thumbColor = KodachromeAmber,
                    activeTrackColor = KodachromeAmber,
                    inactiveTrackColor = DarkroomSurfaceBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("intensity_slider")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Section 2: Film Preset Selector Cards
            Text(
                text = "FILM STOCK PRESETS",
                color = DarkroomTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Kodachrome 64
                FilmPresetCard(
                    preset = FilmPreset.KODACHROME_64,
                    isSelected = selectedPreset == FilmPreset.KODACHROME_64,
                    onClick = { onPresetSelected(FilmPreset.KODACHROME_64) },
                    modifier = Modifier.weight(1f)
                )

                // Ilford HP5 Plus
                FilmPresetCard(
                    preset = FilmPreset.ILFORD_HP5,
                    isSelected = selectedPreset == FilmPreset.ILFORD_HP5,
                    onClick = { onPresetSelected(FilmPreset.ILFORD_HP5) },
                    modifier = Modifier.weight(1f)
                )

                // Original RAW bypass
                FilmPresetCard(
                    preset = FilmPreset.ORIGINAL,
                    isSelected = selectedPreset == FilmPreset.ORIGINAL,
                    onClick = { onPresetSelected(FilmPreset.ORIGINAL) },
                    modifier = Modifier.weight(0.85f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 3: Gallery Pick & Sample Photo Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Upload Photo button
                Button(
                    onClick = onPickPhoto,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkroomSurfaceElevated,
                        contentColor = DarkroomTextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("upload_photo_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Upload Photo from Gallery",
                        tint = KodachromeAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Choose Photo",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Built-in Sample Photos quick toggles
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(SamplePhotos.SAMPLES) { sample ->
                        val isSelected = activeSampleId == sample.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectSample(sample.id) },
                            label = {
                                Text(
                                    text = sample.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = DarkroomSurfaceElevated,
                                labelColor = DarkroomTextSecondary,
                                selectedContainerColor = KodachromeCardBg,
                                selectedLabelColor = KodachromeAmber
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = DarkroomSurfaceBorder,
                                selectedBorderColor = KodachromeAmber
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilmPresetCard(
    preset: FilmPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) preset.accentColor else DarkroomSurfaceBorder
    val containerBg = when {
        isSelected && preset == FilmPreset.KODACHROME_64 -> KodachromeCardBg
        isSelected && preset == FilmPreset.ILFORD_HP5 -> IlfordCardBg
        isSelected -> DarkroomSurfaceElevated
        else -> DarkroomSurfaceElevated.copy(alpha = 0.5f)
    }

    Card(
        modifier = modifier
            .clickable { onClick() }
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
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
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.shortCode,
                    color = if (isSelected) preset.accentColor else DarkroomTextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = preset.accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = preset.displayName,
                color = if (isSelected) DarkroomTextPrimary else DarkroomTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = preset.subtitle,
                color = DarkroomTextMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                    text = "FilmLab Optics & Calibration",
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
                // Resolution Explanation
                InfoSection(
                    title = "Fixed 35mm Scan Resolution (2048px)",
                    description = "In physical photography, film grain is bound to the physical 36×24mm emulsion surface. If grain were added to variable 12MP, 48MP, or 200MP phone sensors without standardization, zooming in would reveal gigantic unnatural digital noise clumps. FilmLab standardizes photos to exactly 2048px on the long edge (standard archival optical lab scan resolution), ensuring that silver halide crystal frequency remains optically authentic."
                )

                // Kodachrome 64
                InfoSection(
                    title = "Kodachrome 64 (1974 - 2009)",
                    description = "Legendary color reversal slide film famous for Steve McCurry's iconic portraits. Developed via the complex K-14 process where dyes were coupled during development. Characterized by warm golden highlights, lush reds, cool cyan shadows, and fine organic dye clouds."
                )

                // Ilford HP5 Plus
                InfoSection(
                    title = "Ilford HP5 Plus (400 ISO)",
                    description = "The classic British panchromatic black-and-white negative film pushed for dramatic micro-contrast. Emulates an optical yellow filter #8 for tonal sky separation, deep D-Max blacks, and tactile cubic silver halide crystalline grain."
                )

                // Intensity
                InfoSection(
                    title = "Intensity Blending",
                    description = "Adjust the slider to dial between subtle analog undertones and 100% full emulsion character. Touch and hold the image anywhere to instantly compare with the raw original."
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
