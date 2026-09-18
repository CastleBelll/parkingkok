package com.parkingkok.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far a surface sits off the page.
 *
 * docs/10_DESIGN_UX_SPEC.md §5 says "avoid excessive shadows; prefer border/surface
 * separation". The mockups in `design-references/` are the reading of that rule this file
 * implements: a card there is clearly lifted off the page tint by a wide, very soft
 * shadow — what §5 rules out is the stacked, hard Material elevation of a components
 * gallery, not the single quiet shadow that tells a card from a wireframe.
 *
 * The values are deliberately small in number. One card elevation, one for the thing that
 * should read as the screen's primary object, and zero for anything drawn inside a card —
 * a shadow inside a shadow is the excess §5 is about.
 */
data class ParkingkokElevation(
    /** Secondary cards: action rows, history rows, settings groups. */
    val card: Dp = 3.dp,
    /** The active-parking hero, the one card that should read as nearest to the eye. */
    val hero: Dp = 8.dp,
    /** Content already inside a card. */
    val flat: Dp = 0.dp,
    /**
     * What colour the shadow is cast in.
     *
     * Not a new colour: it is [BrandPalette.LightTextPrimary] in the light theme, which is
     * the navy the mockups' shadows are tinted with, and the page background itself in the
     * dark theme, where a cast shadow on an almost-black page is noise. Dark separates
     * cards the way §5 asks for instead — surface against background.
     */
    val tint: Color = BrandPalette.LightTextPrimary,
)

internal val LocalParkingkokElevation = staticCompositionLocalOf { ParkingkokElevation() }
