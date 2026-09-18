package com.parkingkok.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.motion.LocalScreenEntry
import com.parkingkok.app.ui.motion.entryStagger
import com.parkingkok.app.ui.motion.rememberScreenEntry

/**
 * The page frame every screen sits in.
 *
 * `safeDrawing` insets are applied here rather than per screen so edge-to-edge
 * (docs/10_DESIGN_UX_SPEC.md §3) is handled once and cannot be forgotten on a new screen.
 * Content scrolls in a [LazyColumn] because the mockups are tall already and a large font
 * scale makes them taller (docs/01_PRODUCT_REQUIREMENTS.md §8).
 *
 * Two things happen here that a screen would otherwise have to remember to do. The page
 * gets its wash of colour behind the cards, and the content gets its entry stagger — each
 * element numbered by the order it was declared in, so no screen counts its own children.
 */
@Composable
fun ParkingkokScreen(
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
    content: ParkingkokListScope.() -> Unit,
) {
    val entry = rememberScreenEntry()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            PageWash()
            CompositionLocalProvider(LocalScreenEntry provides entry) {
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
                        // A fresh scope per invocation, so the numbering restarts with the
                        // content rather than climbing on every recomposition.
                        content = { ParkingkokListScope(this).apply(content) },
                    )
                    footer?.invoke()
                }
            }
        }
    }
}

/**
 * A [LazyListScope] that numbers what a screen declares, so each element can take its
 * place in the screen's entry stagger.
 *
 * It deliberately exposes only the two shapes the app's screens use. A screen that needs
 * more lazy-list machinery than this is a screen doing something the frame should know
 * about.
 */
class ParkingkokListScope internal constructor(private val scope: LazyListScope) {

    private var declared = 0

    /** One element, at [key]. */
    fun item(key: String, content: @Composable () -> Unit) {
        val index = declared++
        scope.item(key = key) {
            Box(Modifier.entryStagger(index)) { content() }
        }
    }

    /** One element per entry of [items], keyed by [key]. */
    fun <T> items(items: List<T>, key: (T) -> Any, itemContent: @Composable (T) -> Unit) {
        val first = declared
        declared += items.size
        scope.itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            Box(Modifier.entryStagger(first + index)) { itemContent(item) }
        }
    }
}

/**
 * The pale blue shapes the mockups lay behind the page.
 *
 * They are why `01-home-main.png` reads as a surface with things resting on it rather than
 * as a flat sheet: the cards have something to be in front of. No new colour — it is the
 * brand primary at a few per cent, which is also why it survives the dark theme without a
 * second set of values.
 *
 * Each one fades to nothing at its own edge. A flat disc at the same opacity draws a
 * visible rim across the page, which is a shape the user can see and wonder about; a blob
 * is only supposed to be felt.
 */
@Composable
private fun PageWash() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { },
    ) {
        val width = size.width
        val height = size.height
        drawBlob(tint, WASH_ALPHA, Offset(width * 1.02f, height * 0.06f), width * 0.62f)
        drawBlob(tint, WASH_ALPHA, Offset(-width * 0.16f, height * 0.46f), width * 0.54f)
        drawBlob(tint, WASH_ALPHA, Offset(width * 1.10f, height * 0.84f), width * 0.50f)
    }
}

private fun DrawScope.drawBlob(tint: Color, alpha: Float, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            // Held flat for the first half so the blob has a body and not just a rim.
            colorStops = arrayOf(
                0f to tint.copy(alpha = alpha),
                0.5f to tint.copy(alpha = alpha),
                1f to Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * The 주차콕 wordmark row at the top of every root screen.
 *
 * The pin, the bell and the gear are all in `01-home-main.png`, and so is the two-line
 * tagline at the right — it is a good part of what the product sounds like, and it used to
 * be demoted to a grey line at the bottom of the page. The bell leads to the system's
 * notification settings for 주차콕: the app has no notification centre of its own, but it
 * does post detection notifications, and where those are turned on and off is a real place
 * to go.
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
    tagline: Boolean = true,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.gutter,
                end = MaterialTheme.spacing.small,
                top = MaterialTheme.spacing.small,
                bottom = MaterialTheme.spacing.small,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        BrandPin(modifier = Modifier.padding(top = MaterialTheme.spacing.tiny))
        Spacer(Modifier.width(MaterialTheme.spacing.medium))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.brand_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.brand_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { actions() }
            }
            // At a large font scale the tagline would push the wordmark off its own line.
            // Dropping it there is the right trade: it is flavour, the wordmark is not.
            if (tagline && LocalDensity.current.fontScale <= TAGLINE_MAX_FONT_SCALE) {
                Text(
                    text = stringResource(R.string.brand_tagline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(
                        end = MaterialTheme.spacing.medium,
                        bottom = MaterialTheme.spacing.tiny,
                    ),
                )
            }
        }
    }
}

/** The gear that opens Settings, sized to the minimum touch target. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    HeaderAction(
        iconRes = R.drawable.ic_settings,
        contentDescription = stringResource(R.string.home_settings),
        onClick = onClick,
    )
}

/** The bell, which leads to where 주차콕's notifications are actually turned on and off. */
@Composable
fun NotificationsAction(onClick: () -> Unit) {
    HeaderAction(
        iconRes = R.drawable.ic_bell,
        contentDescription = stringResource(R.string.home_notification_settings),
        onClick = onClick,
    )
}

@Composable
private fun HeaderAction(iconRes: Int, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(MaterialTheme.spacing.touchTarget)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(HEADER_ICON),
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

/** The sign-off the mockups close every screen with, in the corner they put it in. */
@Composable
fun BrandFooter(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.brand_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.gutter,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}

/** Opacity of the decorative page shapes. Present at a glance, invisible on a card. */
private const val WASH_ALPHA = 0.08f

/** Above this the tagline stops fitting beside the wordmark and is dropped. */
private const val TAGLINE_MAX_FONT_SCALE = 1.3f

/** Header glyphs are a step down from the 24dp default so the pin stays the loudest mark. */
private val HEADER_ICON = 22.dp
