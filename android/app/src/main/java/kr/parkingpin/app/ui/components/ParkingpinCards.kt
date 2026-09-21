package kr.parkingpin.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.parkingpin.app.R
import kr.parkingpin.app.theme.surfaces
import kr.parkingpin.app.theme.spacing

/**
 * The primary card of `01-home-main.png` — a white panel told apart from the page by a
 * hairline.
 *
 * ## Why there is no shadow
 *
 * This card used to cast one, on the argument that a white card on a pale page without a
 * shadow is "a rectangle you have to look for". The premise was right and the conclusion
 * was wrong: what makes the rectangle hard to find is having neither shadow nor border,
 * and the border is the cheaper answer. The design harness in CLAUDE.md forbids card
 * shadows outright — they are one of the patterns that make an app read as
 * machine-generated — and docs/10_DESIGN_UX_SPEC.md §5 asks for background, border and
 * spacing.
 *
 * There is no per-card weight any more. A hero that needs to lead does it with size and
 * position, which is how the mock ranks it too, not by floating nearer the eye.
 */
@Composable
fun ParkingpinCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = MaterialTheme.spacing.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surfaces = MaterialTheme.surfaces
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(surfaces.borderWidth, surfaces.border),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A tinted round icon chip. The mockups use these to give each row a subject at a glance.
 *
 * Always decorative: the adjacent text already says what the row is, and TalkBack
 * announcing both would read everything twice (docs/10_DESIGN_UX_SPEC.md §12).
 */
@Composable
fun IconChip(
    iconRes: Int,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * A titled row inside a card: icon chip, title, supporting line, optional trailing content.
 *
 * [enabled] governs both the tap and the appearance. A disabled row here means "this
 * feature is not in this build" — the caller supplies supporting copy that says so, rather
 * than leaving the user to guess from the grey.
 */
@Composable
fun ParkingpinRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    iconRes: Int? = null,
    // Neutral by default, so a tint is something a row asks for on purpose. When every
    // row picked its own the screens ended up with four accents competing, which is the
    // "버튼/카드마다 색상이 다른 UI" the design harness rules out. Danger is the only
    // caller that overrides this today, and that is a state rather than an accent.
    iconContainerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    iconContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = MaterialTheme.spacing.touchTarget)
            .padding(
                horizontal = MaterialTheme.spacing.large,
                vertical = MaterialTheme.spacing.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            IconChip(
                iconRes = iconRes,
                containerColor = iconContainerColor.copy(alpha = contentAlpha),
                contentColor = iconContentColor.copy(alpha = contentAlpha),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.large))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(MaterialTheme.spacing.small))
            trailing()
        }
    }
}

/** The `>` affordance at the end of a navigating row. Decorative; the row carries the label. */
@Composable
fun RowChevron(enabled: Boolean = true) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
            .copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        modifier = Modifier
            .size(20.dp)
            .clearAndSetSemantics { },
    )
}

/**
 * A small filled label — the `자동` badge in `04-history-list.png`, or `준비 중` on a feature
 * that is not in this build.
 *
 * It carries its own text, never colour alone: docs/01_PRODUCT_REQUIREMENTS.md §8 requires
 * that state is not conveyed by colour on its own.
 */
@Composable
fun StatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    semanticLabel: String? = null,
) {
    Surface(
        modifier = modifier.then(
            if (semanticLabel != null) {
                Modifier.semantics { contentDescription = semanticLabel }
            } else {
                Modifier
            },
        ),
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.tiny,
            ),
        )
    }
}

/** Opacity for a control that exists but cannot be used yet. */
internal const val DISABLED_ALPHA = 0.38f
