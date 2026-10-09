package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.raw.RawFormat
import com.example.raw.RawMetadata
import com.example.ui.theme.DarkroomBlack
import com.example.ui.theme.DarkroomSurface
import com.example.ui.theme.DarkroomSurfaceBorder
import com.example.ui.theme.DarkroomSurfaceElevated
import com.example.ui.theme.DarkroomTextMuted
import com.example.ui.theme.DarkroomTextPrimary
import com.example.ui.theme.DarkroomTextSecondary
import com.example.ui.theme.KodachromeAmber

/**
 * Small, clean button for the top bar to inspect RAW camera telemetry.
 */
@Composable
fun RawInfoButton(
    metadata: RawMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonColor = when {
        metadata.isLeica -> Color(0xFFE53935).copy(alpha = 0.22f)
        metadata.format == RawFormat.SONY_ARW -> Color(0xFFFF9800).copy(alpha = 0.22f)
        metadata.format == RawFormat.DNG -> Color(0xFF29B6F6).copy(alpha = 0.22f)
        else -> DarkroomSurfaceElevated
    }
    val borderColor = when {
        metadata.isLeica -> Color(0xFFE53935).copy(alpha = 0.7f)
        metadata.format == RawFormat.SONY_ARW -> Color(0xFFFF9800).copy(alpha = 0.7f)
        metadata.format == RawFormat.DNG -> Color(0xFF29B6F6).copy(alpha = 0.7f)
        else -> DarkroomSurfaceBorder
    }
    val tintColor = when {
        metadata.isLeica -> Color(0xFFEF5350)
        metadata.format == RawFormat.SONY_ARW -> Color(0xFFFFB74D)
        metadata.format == RawFormat.DNG -> Color(0xFF81D4FA)
        else -> KodachromeAmber
    }

    Surface(
        color = buttonColor,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .testTag("raw_info_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "RAW Camera Telemetry",
                tint = tintColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = when {
                    metadata.isLeica -> "LEICA DNG"
                    metadata.format == RawFormat.SONY_ARW -> "SONY ARW"
                    metadata.format.isRaw -> "RAW INFO"
                    else -> "EXIF"
                },
                color = tintColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (metadata.isColorRecovered) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(KodachromeAmber)
                )
            }
        }
    }
}

/**
 * Compact interactive RAW format & camera telemetry HUD badge for the viewport.
 */
@Composable
fun RawCameraHudBadge(
    metadata: RawMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (metadata.format.isRaw) KodachromeAmber.copy(alpha = 0.8f) else DarkroomSurfaceBorder
        ),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .testTag("raw_hud_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // RAW / Format Indicator Dot
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        when (metadata.format) {
                            RawFormat.SONY_ARW -> Color(0xFFFF9800) // Sony Orange
                            RawFormat.DNG -> Color(0xFF29B6F6)      // Adobe Cyan
                            else -> DarkroomTextSecondary
                        }
                    )
            )

            Spacer(modifier = Modifier.width(6.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (metadata.format.isRaw) metadata.format.badgeLabel else "35mm EXIF",
                        color = when (metadata.format) {
                            RawFormat.SONY_ARW -> Color(0xFFFFB74D)
                            RawFormat.DNG -> Color(0xFF81D4FA)
                            else -> DarkroomTextSecondary
                        },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = metadata.displayCameraName,
                        color = DarkroomTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = metadata.hudTelemetryLine,
                    color = DarkroomTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "EXIF Details",
                tint = DarkroomTextMuted,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

/**
 * Technical RAW & Camera EXIF Inspector Dialog.
 */
@Composable
fun RawMetadataDialog(
    metadata: RawMetadata,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkroomSurfaceElevated,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = when {
                        metadata.isLeica -> Color(0xFFE53935).copy(alpha = 0.2f)
                        metadata.format == RawFormat.SONY_ARW -> Color(0xFFFF9800).copy(alpha = 0.2f)
                        metadata.format == RawFormat.DNG -> Color(0xFF29B6F6).copy(alpha = 0.2f)
                        else -> DarkroomSurface
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when {
                            metadata.isLeica -> Color(0xFFE53935).copy(alpha = 0.6f)
                            metadata.format == RawFormat.SONY_ARW -> Color(0xFFFF9800).copy(alpha = 0.6f)
                            metadata.format == RawFormat.DNG -> Color(0xFF29B6F6).copy(alpha = 0.6f)
                            else -> DarkroomSurfaceBorder
                        }
                    )
                ) {
                    Text(
                        text = metadata.resolvedBadgeLabel,
                        color = when {
                            metadata.isLeica -> Color(0xFFEF5350)
                            metadata.format == RawFormat.SONY_ARW -> Color(0xFFFFB74D)
                            metadata.format == RawFormat.DNG -> Color(0xFF81D4FA)
                            else -> DarkroomTextSecondary
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = metadata.displayCameraName,
                        color = DarkroomTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    metadata.fileName?.let {
                        Text(
                            text = it,
                            color = DarkroomTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Color Recovery Banner (when in-camera B&W profile was bypassed)
                if (metadata.isColorRecovered) {
                    Surface(
                        color = KodachromeAmber.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KodachromeAmber.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = KodachromeAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "ORIGINAL SENSOR COLOR RECOVERED",
                                    color = KodachromeAmber,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "This RAW file was shot with in-camera B&W style. FilmLab restored real full-spectrum sensor RGB color to develop color film stocks.",
                                    color = DarkroomTextPrimary,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
                // Exposure Parameters Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkroomSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "OPTICS & EXPOSURE",
                            color = KodachromeAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        TelemetryRow("Lens", metadata.lensModel ?: "Unspecified")
                        TelemetryRow("Focal Length", metadata.focalLength ?: "—")
                        TelemetryRow("Aperture", metadata.aperture ?: "—")
                        TelemetryRow("Shutter Speed", metadata.shutterSpeed ?: "—")
                        TelemetryRow("ISO Sensitivity", metadata.iso ?: "—")
                        TelemetryRow("Exposure Bias", metadata.exposureBias ?: "±0.0 EV")
                    }
                }

                // Sensor & File Specification Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkroomSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SENSOR & RAW FORMAT",
                            color = when (metadata.format) {
                                RawFormat.SONY_ARW -> Color(0xFFFFB74D)
                                RawFormat.DNG -> Color(0xFF81D4FA)
                                else -> KodachromeAmber
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        TelemetryRow("Format", metadata.format.formatName)
                        metadata.sensorDescription?.let {
                            TelemetryRow("Sensor Architecture", it)
                        }
                        if (metadata.originalWidth > 0 && metadata.originalHeight > 0) {
                            val mp = (metadata.originalWidth.toLong() * metadata.originalHeight.toLong()) / 1_000_000.0
                            TelemetryRow("Resolution", "${metadata.originalWidth} × ${metadata.originalHeight} (${String.format(java.util.Locale.US, "%.1f MP", mp)})")
                        }
                        TelemetryRow("Aspect Ratio", metadata.aspectRatioLabel)
                        TelemetryRow("Color Gamut", metadata.colorSpace ?: "sRGB")
                        metadata.dateTime?.let {
                            TelemetryRow("Captured", it)
                        }
                    }
                }

                // FilmLab Raw Pipeline Note
                Surface(
                    color = DarkroomBlack.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "FILM EMULSION INTEGRATION",
                            color = DarkroomTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (metadata.format.isRaw) {
                                "This RAW frame contains 14-bit linear dynamic range. FilmLab's analog sensitometry maps the deep uncompressed shadow latitude and highlight rolloff directly into silver halide crystals and subtractive dye layers without digital clipping."
                            } else {
                                "FilmLab processes 35mm scans using calibrated sensitometric curves (Toe, Straight Line, Shoulder) and dye coupling physics."
                            },
                            color = DarkroomTextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
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
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = DarkroomTextSecondary,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = DarkroomTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * File Import Source Selector Dialog (Choice between RAW Document Picker & Photo Gallery).
 */
@Composable
fun ImportSourceDialog(
    onPickGallery: () -> Unit,
    onPickRaw: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkroomSurfaceElevated,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = KodachromeAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Import Image / RAW File",
                    color = DarkroomTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select source format:",
                    color = DarkroomTextSecondary,
                    fontSize = 12.sp
                )

                // Option 1: Open RAW File (DNG, Sony ARW)
                Surface(
                    color = DarkroomSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onPickRaw()
                        }
                        .testTag("import_raw_option")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFFFF9800).copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Camera,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Camera RAW File",
                                    color = DarkroomTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = Color(0xFFFF9800).copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = ".ARW / .DNG",
                                        color = Color(0xFFFFB74D),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Sony a7 cameras (.ARW), Adobe DNG (.DNG), Leica, or external SD card / OTG storage.",
                                color = DarkroomTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                // Option 2: Standard Photo Gallery
                Surface(
                    color = DarkroomSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkroomSurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onPickGallery()
                        }
                        .testTag("import_gallery_option")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = DarkroomSurfaceElevated,
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = DarkroomTextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Photo Library",
                                color = DarkroomTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Standard photos (JPEG, PNG, WebP) from device gallery.",
                                color = DarkroomTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkroomSurfaceElevated,
                    contentColor = DarkroomTextSecondary
                )
            ) {
                Text("Cancel")
            }
        }
    )
}
