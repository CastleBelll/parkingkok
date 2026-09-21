package kr.parkingpin.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kr.parkingpin.app.MainActivity
import kr.parkingpin.app.R
import kr.parkingpin.app.domain.parking.ElapsedTime
import kr.parkingpin.app.domain.widget.ParkingWidgetProjection
import kr.parkingpin.app.ui.format.elapsedResource

/**
 * The widget, which is the hero card of `design-references/01-home-main.png` with the map
 * thumbnail and the primary action removed (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a).
 *
 * The order is the card's order and docs/10_DESIGN_UX_SPEC.md §6's: the floor as the one
 * large element, `zone · spot` beneath it, then the elapsed duration. Nothing here reads
 * storage or decides a rule — it draws [projection] and nothing else, which is what keeps
 * product state out of the widget (docs/06 §7).
 *
 * No coordinate, address or photo appears, and none is reachable: [ParkingWidgetProjection]
 * has no field that could carry one (docs/09_SECURITY_PRIVACY_COMPLIANCE.md).
 *
 * @param nowMillis evaluated when the host asks for a redraw. The elapsed line is
 *   therefore as fresh as the last update, not live to the second — Android gives a
 *   widget no cheaper tick than `updatePeriodMillis`, and the floor, not the duration, is
 *   what this widget is for.
 */
@Composable
internal fun ParkingWidgetContent(projection: ParkingWidgetProjection, nowMillis: Long) {
    val wide = LocalSize.current.width >= ParkingWidgetSizes.WIDE.width

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(CARD_RADIUS)
            .padding(if (wide) WIDE_PADDING else COMPACT_PADDING)
            // The whole card opens the app. docs/02 §2 gives the widget no job of its own
            // beyond the stepper, so anywhere that is not a key is a way back in.
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!projection.isActive) {
            NotParkedLine()
        } else {
            Row(
                // Fills the card so the summary's weight pushes the stepper to the far
                // edge instead of leaving it hanging beside the text.
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    ParkingSummary(projection = projection, nowMillis = nowMillis, wide = wide)
                }
                if (wide && projection.showsStepper) {
                    Spacer(GlanceModifier.width(STEPPER_GAP))
                    FloorStepper(projection)
                }
            }
        }
    }
}

/**
 * docs/06 §7a: with no active parking the widget shows a single line inviting the user to
 * open the app. One line, no call to action button — the whole card is already the tap
 * target, and a 2x2 cell has no room to say the same thing twice.
 */
@Composable
private fun NotParkedLine() {
    Text(
        text = LocalContext.current.getString(R.string.widget_empty),
        maxLines = EMPTY_MAX_LINES,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = BODY_SIZE,
            textAlign = TextAlign.Center,
        ),
        // Centred by the parent column, which is why this fills the width but not the
        // height: a 2x2 with one sentence pinned to the top reads as a loading state.
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

/** Floor, then `zone · spot`, then elapsed — on every size (§7a). */
@Composable
private fun ParkingSummary(
    projection: ParkingWidgetProjection,
    nowMillis: Long,
    wide: Boolean,
) {
    val context = LocalContext.current
    val floorLabel = projection.floorLabel ?: context.getString(R.string.home_no_floor)
    val spoken = projection.floorSpokenLabel ?: floorLabel

    Text(
        text = context.getString(R.string.home_active_title),
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = LABEL_SIZE),
    )
    Spacer(GlanceModifier.height(TIGHT_GAP))
    Text(
        text = floorLabel,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = heroSize(floorLabel, wide),
            fontWeight = FontWeight.Bold,
        ),
        // One node for the whole reading, so TalkBack says `현재 주차 위치, 지하 3층`
        // instead of spelling `B3` letter by letter.
        modifier = GlanceModifier.semantics {
            contentDescription = "${context.getString(R.string.home_active_title)}, $spoken"
        },
    )

    // §7a (2026-09-20): every size carries `zone · spot`. It costs the 2x2 hero a few
    // points, and it is worth them — a floor without the zone still leaves the user
    // searching the level.
    val zoneSpot = projection.zoneSpot
    if (zoneSpot != null) {
        Spacer(GlanceModifier.height(TIGHT_GAP))
        Text(
            text = zoneSpot,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = SUPPORT_SIZE),
        )
    }

    Spacer(GlanceModifier.height(TIGHT_GAP))
    Text(
        text = elapsedLabel(context, projection.startedAtMillis, nowMillis),
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY_SIZE),
    )
}

/**
 * How large the floor is drawn.
 *
 * The only reason to look at this widget is "where is my car", so the floor is the one
 * element that has to read at a glance — nothing else on the card comes near it, and
 * there is no brand mark or ornament competing for the same attention. The 4x2 has the
 * room to go further than the 2x2 and does.
 *
 * The 2x2 gave up 4sp when `zone · spot` joined it (§7a, 2026-09-20): four lines in a
 * 2x2 cell need the room, and a slightly smaller floor that tells you the zone beats a
 * slightly larger one that does not.
 *
 * A free-text floor can be a whole phrase, so it drops a step rather than truncating —
 * the same concession the home hero makes. A floor the user cannot read is worse than one
 * that is not enormous.
 */
private fun heroSize(floorLabel: String, wide: Boolean): TextUnit = when {
    floorLabel.length > HERO_MAX_CHARS -> HERO_SMALL_SIZE
    wide -> HERO_WIDE_SIZE
    else -> HERO_SIZE
}

/** The same sentence the home card shows, read from the same string resources. */
private fun elapsedLabel(context: Context, startedAtMillis: Long, nowMillis: Long): String {
    val (id, args) = elapsedResource(ElapsedTime.since(startedAtMillis, nowMillis))
    return context.getString(id, *args)
}

/**
 * The `-` / `+` keys, Plus only (docs/04_ANDROID_IMPLEMENTATION.md §10).
 *
 * Each key sends a delta, never a resulting floor, so two taps landing together move two
 * floors (docs/06 §7a). A key the floor domain would reject is drawn inert rather than
 * hidden, so the pair does not jump around as the car moves up and down a garage.
 */
@Composable
private fun FloorStepper(projection: ParkingWidgetProjection) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepperKey(
            glyph = MINUS_GLYPH,
            description = context.getString(R.string.home_floor_decrease),
            enabled = projection.canStepDown,
            sessionId = projection.sessionId,
            delta = -1,
        )
        Spacer(GlanceModifier.height(TIGHT_GAP))
        StepperKey(
            glyph = PLUS_GLYPH,
            description = context.getString(R.string.home_floor_increase),
            enabled = projection.canStepUp,
            sessionId = projection.sessionId,
            delta = +1,
        )
    }
}

@Composable
private fun StepperKey(
    glyph: String,
    description: String,
    enabled: Boolean,
    sessionId: String?,
    delta: Int,
) {
    val base = GlanceModifier
        .size(width = KEY_WIDTH, height = KEY_HEIGHT)
        .background(
            if (enabled) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant,
        )
        .cornerRadius(KEY_RADIUS)
        .semantics { contentDescription = description }
    Box(
        modifier = if (enabled && sessionId != null) {
            base.clickable(ParkingWidgetFloorStepAction.action(sessionId, delta))
        } else {
            base
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = TextStyle(
                color = if (enabled) {
                    GlanceTheme.colors.onPrimaryContainer
                } else {
                    GlanceTheme.colors.onSurfaceVariant
                },
                fontSize = KEY_GLYPH_SIZE,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

// The mockup's glyphs. Drawn as text rather than as the app's vector icons because a
// RemoteViews tint is not available below API 31 and an untinted glyph would be invisible
// on the dark scheme.
private const val MINUS_GLYPH = "−"
private const val PLUS_GLYPH = "+"

/** Past this the floor is free text rather than `B3`/`3F`, and needs the smaller size. */
private const val HERO_MAX_CHARS = 4
private const val EMPTY_MAX_LINES = 3

private val CARD_RADIUS: Dp = 16.dp
private val COMPACT_PADDING: Dp = 12.dp
private val WIDE_PADDING: Dp = 16.dp
private val TIGHT_GAP: Dp = 4.dp
private val STEPPER_GAP: Dp = 12.dp
private val KEY_WIDTH: Dp = 52.dp
private val KEY_HEIGHT: Dp = 40.dp
private val KEY_RADIUS: Dp = 12.dp

private val LABEL_SIZE = 12.sp
private val HERO_SIZE = 30.sp
private val HERO_WIDE_SIZE = 40.sp
private val HERO_SMALL_SIZE = 20.sp
private val SUPPORT_SIZE = 15.sp
private val BODY_SIZE = 12.sp
private val KEY_GLYPH_SIZE = 22.sp
