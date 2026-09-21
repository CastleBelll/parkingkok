package com.parkingpin.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale, the one token family Material 3 has no slot for.
 *
 * Colour, type and shape all map onto `MaterialTheme`; spacing does not, so it travels on
 * its own CompositionLocal rather than as bare dp literals sprinkled through screens —
 * same reasoning as docs/10_DESIGN_UX_SPEC.md §2 gives for colour.
 *
 * The steps are a 4dp grid. `gutter` is the screen's horizontal inset and `card` the
 * padding inside a primary card; both are named after the job so the mockup density in
 * `design-references/` can be retuned in one place.
 */
data class ParkingkokSpacing(
    val hairline: Dp = 2.dp,
    val tiny: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val xLarge: Dp = 20.dp,
    val xxLarge: Dp = 28.dp,
    /** Screen-edge inset, matching the mockups' card margin. */
    val gutter: Dp = 20.dp,
    /** Padding inside a primary card. */
    val card: Dp = 20.dp,
    /** Minimum touch target (docs/01_PRODUCT_REQUIREMENTS.md §8). */
    val touchTarget: Dp = 48.dp,
)

internal val LocalParkingkokSpacing = staticCompositionLocalOf { ParkingkokSpacing() }
