package com.parkingkok.app.theme

import androidx.compose.ui.graphics.Color

/**
 * The 주차핀 palette from docs/10_DESIGN_UX_SPEC.md §2.
 *
 * This file is the only place in the app where a colour literal is allowed to appear.
 * §2 is explicit — "implement as semantic assets/theme tokens, never scatter hex values
 * in feature code" — so screens read colours through `MaterialTheme.colorScheme` and
 * never construct a [Color]. The compensating tokens below (`*Container`, `*Muted`) are
 * derived shades the mockups use for icon chips and filled rows; they are named after the
 * Material role they fill so the mapping in [ParkingkokTheme] stays readable.
 */
internal object BrandPalette {

    // --- Light (docs/10 §2) ---
    val LightBackground = Color(0xFFF7F8FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightTextPrimary = Color(0xFF111827)
    val LightTextSecondary = Color(0xFF6B7280)
    val LightPrimary = Color(0xFF2563EB)
    val LightAccent = Color(0xFF14B8A6)
    val LightDivider = Color(0xFFE5E7EB)
    val LightDanger = Color(0xFFEF4444)

    /** Icon chips and the `-`/`+` floor keys read as a tinted wash of [LightPrimary]. */
    val LightPrimaryContainer = Color(0xFFDCE9FE)
    val LightOnPrimaryContainer = Color(0xFF1D4ED8)

    /** The mint counterpart, used for the location chip and the "자동" history badge. */
    val LightAccentContainer = Color(0xFFCCF5EE)
    val LightOnAccentContainer = Color(0xFF0F766E)

    /** Inset rows and segmented backgrounds; one step off the page, not off the card. */
    val LightSurfaceVariant = Color(0xFFEFF3FA)
    val LightDangerContainer = Color(0xFFFEE2E2)
    val LightOnDangerContainer = Color(0xFFB91C1C)

    // --- Dark (docs/10 §2) ---
    val DarkBackground = Color(0xFF0F172A)
    val DarkSurface = Color(0xFF172033)
    val DarkTextPrimary = Color(0xFFF8FAFC)
    val DarkTextSecondary = Color(0xFF94A3B8)
    val DarkPrimary = Color(0xFF3B82F6)
    val DarkAccent = Color(0xFF2DD4BF)

    /**
     * §2 lists no dark `divider`/`danger`. A separator has to stay a separator in the
     * dark, so it is derived from the dark surface family rather than reusing the light
     * token, which would glare.
     */
    val DarkDivider = Color(0xFF2B3852)
    val DarkDanger = Color(0xFFF87171)

    val DarkPrimaryContainer = Color(0xFF1E3A6B)
    val DarkOnPrimaryContainer = Color(0xFFBFDBFE)
    val DarkAccentContainer = Color(0xFF13453F)
    val DarkOnAccentContainer = Color(0xFF99F6E4)
    val DarkSurfaceVariant = Color(0xFF1E293B)
    val DarkDangerContainer = Color(0xFF4C1D1D)
    val DarkOnDangerContainer = Color(0xFFFECACA)

    /** Text drawn on top of [LightPrimary]/[DarkPrimary] fills. */
    val OnPrimary = Color(0xFFFFFFFF)
}
