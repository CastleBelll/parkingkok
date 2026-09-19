package com.parkingkok.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R

/**
 * The 주차핀 mark, drawn once as artwork and shown everywhere.
 *
 * This used to be a pin composed from a shape and a car glyph in code, with a hand-tuned
 * point and a hand-placed icon. The app now ships a real icon, and an approximation of it
 * in the header meant the mark the user tapped in the launcher and the mark at the top of
 * the app were two different drawings. It is the same asset now, and the shape arithmetic
 * that kept them nearly-but-not-quite aligned is gone.
 *
 * Decorative: the wordmark beside it already says what this is (docs/10 §12).
 */
@Composable
fun BrandPin(modifier: Modifier = Modifier, height: Dp = 40.dp) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = modifier
            .height(height)
            .width(height * MARK_ASPECT)
            .clearAndSetSemantics { },
    )
}

/** The artwork's own proportions, so the mark is never stretched. */
private const val MARK_ASPECT = 0.847f
