package com.example.compx551a4.ui.theme

import androidx.compose.ui.graphics.Color

// [D] Palette. Low-chroma teal/slate: this app is looked at while concentrating, and during
// calibration it is the visual field itself, so nothing here is allowed to be loud.

// --- Light ---
val TealPrimary = Color(0xFF2F6F6A)
val TealOnPrimary = Color(0xFFFFFFFF)
val TealContainer = Color(0xFFB8DEDA)
val TealOnContainer = Color(0xFF05201E)
val SlateSecondary = Color(0xFF4A635F)
val SlateContainer = Color(0xFFCCE8E3)
val SlateOnContainer = Color(0xFF06201D)
val SandTertiary = Color(0xFF4B607C)
val SandContainer = Color(0xFFD3E4FF)
val SandOnContainer = Color(0xFF041C35)
val SurfaceLight = Color(0xFFF5FAF8)
val OnSurfaceLight = Color(0xFF171D1C)
val SurfaceVariantLight = Color(0xFFDAE5E2)
val OnSurfaceVariantLight = Color(0xFF3F4947)
val OutlineLight = Color(0xFF6F7977)

// The surface-container family. Material 3 draws navigation bars, menus and elevated surfaces from
// these; leaving them unset silently falls back to the baseline purple scheme.
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFEFF5F3)
val SurfaceContainerLight = Color(0xFFE9F0EE)
val SurfaceContainerHighLight = Color(0xFFE3EAE8)
val SurfaceContainerHighestLight = Color(0xFFDDE5E2)
val SurfaceDimLight = Color(0xFFD5DEDB)
val SurfaceBrightLight = Color(0xFFF5FAF8)

// --- Dark ---
val TealPrimaryDark = Color(0xFF9DCFC9)
val TealOnPrimaryDark = Color(0xFF003733)
val TealContainerDark = Color(0xFF1E4F4B)
val TealOnContainerDark = Color(0xFFB8DEDA)
val SlateSecondaryDark = Color(0xFFB0CCC7)
val SlateContainerDark = Color(0xFF324B48)
val SandTertiaryDark = Color(0xFFB3C8E8)
val SandContainerDark = Color(0xFF334863)
val SurfaceDark = Color(0xFF0E1514)
val OnSurfaceDark = Color(0xFFDDE4E2)
val SurfaceVariantDark = Color(0xFF3F4947)
val OnSurfaceVariantDark = Color(0xFFBEC9C6)
val OutlineDark = Color(0xFF899391)

val SurfaceContainerLowestDark = Color(0xFF090F0E)
val SurfaceContainerLowDark = Color(0xFF161D1C)
val SurfaceContainerDark = Color(0xFF1A2120)
val SurfaceContainerHighDark = Color(0xFF252C2B)
val SurfaceContainerHighestDark = Color(0xFF303736)
val SurfaceDimDark = Color(0xFF0E1514)
val SurfaceBrightDark = Color(0xFF343B3A)

// --- Semantic ---
// Never used as the only signal: every state that uses these also carries an icon and a label, so
// the UI stays readable for the ~8% of men with a colour vision deficiency.
val FocusedLight = Color(0xFF2E6B45)
val FocusedDark = Color(0xFF8FD6A8)
val IdleLight = Color(0xFF6F7977)
val IdleDark = Color(0xFF899391)
val UnknownLight = Color(0xFF7A5A2E)
val UnknownDark = Color(0xFFE0BE86)

val ContactGoodLight = Color(0xFF2E6B45)
val ContactGoodDark = Color(0xFF8FD6A8)
val ContactFairLight = Color(0xFF8A6A1F)
val ContactFairDark = Color(0xFFE2C377)
val ContactPoorLight = Color(0xFF9B2C2C)
val ContactPoorDark = Color(0xFFF2B8B5)

// --- Calibration ---
// Fixed regardless of theme or dynamic colour. DESIGN.md §2 requires matched visual input across
// conditions; a wallpaper-derived palette or a mid-task dark-mode switch would confound exactly the
// comparison the whole calibration rests on.
val CalibrationBackground = Color(0xFF14181A)
val CalibrationForeground = Color(0xFFE6EAEC)
val CalibrationAccent = Color(0xFF9DCFC9)
