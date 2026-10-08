package com.example.film

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.DarkroomTextSecondary
import com.example.ui.theme.IlfordSilver
import com.example.ui.theme.KodachromeAmber

enum class FilmPreset(
    val displayName: String,
    val shortCode: String,
    val subtitle: String,
    val chemistryInfo: String,
    val grainDescription: String,
    val accentColor: Color
) {
    KODACHROME_64(
        displayName = "Kodachrome 64",
        shortCode = "K-64",
        subtitle = "Warm Vintage Reversal",
        chemistryInfo = "K-14 Subtractive Dye Coupling (1974-2009)",
        grainDescription = "Fine organic dye clouds concentrated in warm midtones and shadows",
        accentColor = KodachromeAmber
    ),
    ILFORD_HP5(
        displayName = "Ilford HP5 Plus",
        shortCode = "HP5+",
        subtitle = "High-Contrast B&W",
        chemistryInfo = "Panchromatic 400 ISO Silver Halide (UK)",
        grainDescription = "Prominent cubic silver halide crystalline grain with deep D-Max blacks",
        accentColor = IlfordSilver
    ),
    ORIGINAL(
        displayName = "Original",
        shortCode = "RAW",
        subtitle = "Digital Baseline",
        chemistryInfo = "Standardized 35mm Scan Resolution",
        grainDescription = "Unfiltered 2048px lab scan geometry without analog grain emulation",
        accentColor = DarkroomTextSecondary
    )
}
