package com.parkingkok.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.theme.spacing

/**
 * The page frame every screen sits in.
 *
 * `safeDrawing` insets are applied here rather than per screen so edge-to-edge
 * (docs/10_DESIGN_UX_SPEC.md §3) is handled once and cannot be forgotten on a new screen.
 * Content scrolls in a [LazyColumn] because the mockups are tall already and a large font
 * scale makes them taller (docs/01_PRODUCT_REQUIREMENTS.md §8).
 */
@Composable
fun ParkingkokScreen(
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            header?.invoke()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = MaterialTheme.spacing.gutter,
                    end = MaterialTheme.spacing.gutter,
                    top = MaterialTheme.spacing.small,
                    bottom = MaterialTheme.spacing.xxLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                content = content,
            )
            footer?.invoke()
        }
    }
}

/**
 * The 주차콕 wordmark row at the top of every root screen.
 *
 * The mockups put a bell and a gear here. Only the gear is wired: there is no notification
 * centre in this build, and a bell that opens nothing would be a promise the app does not
 * keep.
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.gutter,
                end = MaterialTheme.spacing.small,
                top = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_car),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacing.medium))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.brand_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}

/** The gear that opens Settings, sized to the minimum touch target. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(MaterialTheme.spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.home_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The header for a pushed screen: back affordance plus title.
 *
 * A plain row rather than a `TopAppBar` so the title sits on the page background, as in
 * `03-parking-detail.png`, instead of on a raised bar.
 */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.small,
                end = MaterialTheme.spacing.small,
                top = MaterialTheme.spacing.small,
                bottom = MaterialTheme.spacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(MaterialTheme.spacing.touchTarget)) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.spacing.tiny)
                .semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** A section label above a group of cards (`05-settings.png`). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .padding(top = MaterialTheme.spacing.small, bottom = MaterialTheme.spacing.tiny)
            .semantics { heading() },
    )
}

/** The reassurance line the mockups close every screen with. */
@Composable
fun BrandFooter(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.brand_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.gutter,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}
