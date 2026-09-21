package com.parkingpin.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * docs/10_DESIGN_UX_SPEC.md §5 corner radii, mapped onto the three Material roles the app
 * actually uses so feature code can say `MaterialTheme.shapes.extraLarge` instead of
 * repeating a dp value.
 *
 * | §5 role        | radius | Material slot |
 * |----------------|--------|---------------|
 * | primary card   | 22     | extraLarge    |
 * | secondary row  | 16     | medium        |
 * | button         | 16     | small         |
 *
 * `large` sits between the two so Material components the app does not style by hand
 * (dialogs, sheets) land somewhere sensible instead of on the Material default.
 */
internal val ParkingkokShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(22.dp),
)
