package com.parkingpin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parkingpin.app.R
import com.parkingpin.app.theme.spacing
import com.parkingpin.app.ui.motion.LocalScreenEntry
import com.parkingpin.app.ui.motion.entryStagger
import com.parkingpin.app.ui.motion.rememberScreenEntry

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
fun ParkingpinScreen(
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
    content: ParkingpinListScope.() -> Unit,
) {
    val entry = rememberScreenEntry()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
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
                        content = { ParkingpinListScope(this).apply(content) },
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
class ParkingpinListScope internal constructor(private val scope: LazyListScope) {

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
 * The 주차핀 wordmark row at the top of every root screen.
 *
 * The pin, the bell and the gear are all in `01-home-main.png`. The mock's two-line
 * tagline is not here: it made the right column taller than the wordmark beside it, which
 * pushed 주차핀 up into the corner, and it was a second caption on a header that already
 * has one. The bell opens the notification history (docs/10_DESIGN_UX_SPEC.md §7b) —
 * what the app raised and what became of it. It used to open the system's notification
 * settings, which answered a question nobody had; those switches stay in 설정 → 알림.
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
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
        // Centred, two columns. The right side used to carry the mock's handwritten aside
        // under the controls, which made that column two lines taller than the wordmark
        // beside it — top-aligned, that pushed 주차핀 up into the corner. The aside is
        // gone, so the name and the controls sit level.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandPin()
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
        if (actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
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

/**
 * The bell, which opens the notification history (docs/10_DESIGN_UX_SPEC.md §7b).
 *
 * [unanswered] puts §7b's dot on it. **A dot, not a count**: §12 allows at most one
 * pending candidate, so a number would only ever be `1` and would invite the reader to
 * wonder when it will be `2`. It is the whole reason the screen exists — a notification
 * swiped away in the car is otherwise lost until it expires — so it is drawn in the
 * primary, the one accent the header already carries, rather than adding a second.
 *
 * The label changes with it. docs/01_PRODUCT_REQUIREMENTS.md §8 does not allow colour to
 * be the only carrier of a state, and a dot TalkBack never mentions is exactly that.
 */
@Composable
fun NotificationsAction(unanswered: Boolean, onClick: () -> Unit) {
    HeaderAction(
        iconRes = R.drawable.ic_bell,
        contentDescription = stringResource(
            if (unanswered) R.string.home_notifications_unanswered else R.string.home_notifications,
        ),
        onClick = onClick,
        badged = unanswered,
    )
}

@Composable
private fun HeaderAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    badged: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(MaterialTheme.spacing.touchTarget)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(HEADER_ICON),
            )
            if (badged) {
                // Offset onto the bell's upper corner, and ringed in the page colour so
                // it stays a separate mark instead of merging with the glyph. The ring is
                // a border, not a shadow — docs/10 §5, and the harness in CLAUDE.md.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = DOT_OFFSET, y = -DOT_OFFSET)
                        .size(DOT_SIZE)
                        .border(DOT_RING, MaterialTheme.colorScheme.background, CircleShape)
                        .padding(DOT_RING)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
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
    /** Empty for a screen that carries its own title in the content below. */
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
        if (title.isEmpty()) {
            // A screen whose own first line is its title — the confirmation screen, where
            // 주차한 것 같아요 has to be the largest thing on screen (docs/10 §7a). Repeating
            // it up here would put the most important sentence in the app on the page
            // twice, at two sizes.
            Spacer(Modifier.weight(1f))
        } else {
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
        }
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

/** Opacity of the decorative page shapes. Present at a glance, invisible on a card. */


/** Header glyphs are a step down from the 24dp default so the pin stays the loudest mark. */
private val HEADER_ICON = 22.dp

/** §7b's dot: big enough to see across a car park, small enough not to be a badge. */
private val DOT_SIZE = 10.dp

private val DOT_RING = 1.5.dp

/** Sits it on the bell's shoulder rather than beside it. */
private val DOT_OFFSET = 1.dp
