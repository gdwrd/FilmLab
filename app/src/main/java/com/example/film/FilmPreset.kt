package com.example.film

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.CineStillBorder
import com.example.ui.theme.CineStillCardBg
import com.example.ui.theme.CineStillRed
import com.example.ui.theme.DarkroomSurfaceBorder
import com.example.ui.theme.DarkroomSurfaceElevated
import com.example.ui.theme.DarkroomTextSecondary
import com.example.ui.theme.IlfordBorder
import com.example.ui.theme.IlfordCardBg
import com.example.ui.theme.IlfordSilver
import com.example.ui.theme.KodachromeAmber
import com.example.ui.theme.KodachromeBorder
import com.example.ui.theme.KodachromeCardBg
import com.example.ui.theme.PortraBorder
import com.example.ui.theme.PortraCardBg
import com.example.ui.theme.PortraPeach
import com.example.ui.theme.Pro400hBorder
import com.example.ui.theme.Pro400hCardBg
import com.example.ui.theme.Pro400hMint
import com.example.ui.theme.TriXBorder
import com.example.ui.theme.TriXCardBg
import com.example.ui.theme.TriXCharcoal
import com.example.ui.theme.VelviaBorder
import com.example.ui.theme.VelviaCardBg
import com.example.ui.theme.VelviaEmerald

enum class FilmPreset(
    val displayName: String,
    val shortCode: String,
    val subtitle: String,
    val category: String,
    val isoRating: String,
    val chemistryInfo: String,
    val grainDescription: String,
    val accentColor: Color,
    val cardBgColor: Color,
    val borderColor: Color
) {
    KODACHROME_64(
        displayName = "Kodachrome 64",
        shortCode = "K-64",
        subtitle = "Warm Vintage Reversal",
        category = "REVERSAL SLIDE",
        isoRating = "ISO 64",
        chemistryInfo = "K-14 Subtractive Dye Coupling (1974-2009)",
        grainDescription = "Fine organic dye clouds concentrated in warm midtones and shadows",
        accentColor = KodachromeAmber,
        cardBgColor = KodachromeCardBg,
        borderColor = KodachromeBorder
    ),
    PORTRA_400(
        displayName = "Kodak Portra 400",
        shortCode = "P-400",
        subtitle = "Warm Pastel Portrait",
        category = "COLOR NEGATIVE",
        isoRating = "ISO 400",
        chemistryInfo = "C-41 Optimized Micro-Emulsion (USA)",
        grainDescription = "Ultra-fine T-GRAIN tabular crystals with silky, uniform midtone texture",
        accentColor = PortraPeach,
        cardBgColor = PortraCardBg,
        borderColor = PortraBorder
    ),
    FUJI_VELVIA_50(
        displayName = "Fujifilm Velvia 50",
        shortCode = "RVP-50",
        subtitle = "Ultra-Vivid Landscape",
        category = "REVERSAL SLIDE",
        isoRating = "ISO 50",
        chemistryInfo = "E-6 High-Density Chrome (Japan)",
        grainDescription = "Microscopic RMS-9 slide dye clouds with razor-sharp micro-contrast",
        accentColor = VelviaEmerald,
        cardBgColor = VelviaCardBg,
        borderColor = VelviaBorder
    ),
    CINESTILL_800T(
        displayName = "CineStill 800T",
        shortCode = "800T",
        subtitle = "Tungsten & Red Halation",
        category = "TUNGSTEN MOTION",
        isoRating = "ISO 800",
        chemistryInfo = "Eastman Kodak Vision3 500T (Non-Remjet C-41)",
        grainDescription = "Motion picture dye clouds with signature red highlight halation bloom",
        accentColor = CineStillRed,
        cardBgColor = CineStillCardBg,
        borderColor = CineStillBorder
    ),
    FUJI_PRO_400H(
        displayName = "Fujifilm Pro 400H",
        shortCode = "400H",
        subtitle = "Airy Pastel & Mint",
        category = "COLOR NEGATIVE",
        isoRating = "ISO 400",
        chemistryInfo = "C-41 4th Color-Sensitive Cyan Layer (Japan)",
        grainDescription = "Soft, luminous negative dye structure with clean porcelain skin tones",
        accentColor = Pro400hMint,
        cardBgColor = Pro400hCardBg,
        borderColor = Pro400hBorder
    ),
    ILFORD_HP5(
        displayName = "Ilford HP5 Plus",
        shortCode = "HP5+",
        subtitle = "High-Contrast B&W",
        category = "BLACK & WHITE",
        isoRating = "ISO 400",
        chemistryInfo = "Panchromatic 400 ISO Silver Halide (UK)",
        grainDescription = "Prominent cubic silver halide crystalline grain with deep D-Max blacks",
        accentColor = IlfordSilver,
        cardBgColor = IlfordCardBg,
        borderColor = IlfordBorder
    ),
    KODAK_TRI_X(
        displayName = "Kodak Tri-X 400",
        shortCode = "400TX",
        subtitle = "Gritty Street Photojournalism",
        category = "BLACK & WHITE",
        isoRating = "ISO 400",
        chemistryInfo = "Panchromatic Silver Halide / D-76 Developer (USA)",
        grainDescription = "Aggressive, tactile silver clump grain with rich charcoal blacks",
        accentColor = TriXCharcoal,
        cardBgColor = TriXCardBg,
        borderColor = TriXBorder
    ),
    ORIGINAL(
        displayName = "Original",
        shortCode = "RAW",
        subtitle = "Digital Baseline",
        category = "DIGITAL BASELINE",
        isoRating = "BASE",
        chemistryInfo = "Standardized 35mm Scan Resolution",
        grainDescription = "Unfiltered 2048px lab scan geometry without analog grain emulation",
        accentColor = DarkroomTextSecondary,
        cardBgColor = DarkroomSurfaceElevated,
        borderColor = DarkroomSurfaceBorder
    )
}
