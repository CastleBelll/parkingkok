package com.parkingpin.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a surface is told apart from the page behind it.
 *
 * This used to be `ParkingpinElevation`, and it cast shadows. The design harness in
 * CLAUDE.md now forbids them: a heavy card shadow is one of the specific patterns that
 * makes an app read as machine-generated, and docs/10_DESIGN_UX_SPEC.md §5 asks for
 * background, border and spacing instead. The old file argued that a white card on a pale
 * page is "a rectangle you have to look for" without a shadow. That is true of a card with
 * neither shadow *nor* border — the answer is the border, not the shadow.
 *
 * The type is named for what it produces so nobody has to open it to find out that an
 * "elevation" token draws an outline.
 */
data class ParkingpinSurfaces(
    /** The hairline that separates a surface from the page. */
    val border: Color,
    /**
     * One hairline, at the thinnest width that survives rounding on every density. Two
     * weights would invite a hierarchy of borders, which is the shadow problem again in a
     * different notation.
     */
    val borderWidth: Dp = 1.dp,
)

internal val LocalParkingpinSurfaces = staticCompositionLocalOf {
    ParkingpinSurfaces(border = BrandPalette.LightDivider)
}
