package com.parkingpin.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.parkingpin.app.theme.spacing
import com.parkingpin.app.ui.motion.pressScale

/**
 * A screen's one primary action: an icon and a label, with a line underneath explaining
 * what pressing it will do.
 *
 * Defined once because `01-home-main.png` and `03-parking-detail.png` end with the same
 * button, and two copies of it had already drifted. What the mockup gets right and the
 * copies did not is the room: the label sits in the middle of its own space and the
 * caption has a gap above it, instead of both being pressed into a slab of blue.
 */
@Composable
fun PrimaryCtaButton(
    iconRes: Int,
    label: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.large,
            vertical = MaterialTheme.spacing.large,
        ),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PRIMARY_CTA_MIN_HEIGHT)
            .pressScale(interactionSource),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(CTA_ICON),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Text(text = label, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(MaterialTheme.spacing.tiny))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                // Supporting copy, not a second label. Carried by the label above it, so
                // the lower contrast never has to be the only thing the user reads.
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = CTA_CAPTION_ALPHA),
            )
        }
    }
}

/** Room for a label line and a caption line with a gap between them. */
private val PRIMARY_CTA_MIN_HEIGHT = 76.dp

private val CTA_ICON = 20.dp

private const val CTA_CAPTION_ALPHA = 0.82f
