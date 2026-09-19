package com.parkingkok.app.ui.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Whether motion plays, for everything below [com.parkingkok.app.theme.ParkingkokTheme].
 *
 * Read it rather than querying the system: one reader means one place that can get the
 * accessibility contract wrong, and a `@Preview` or a test can override it.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** The screen entry timeline, 0..1. Provided by `ParkingkokScreen`; 1 means "already in". */
val LocalScreenEntry = staticCompositionLocalOf<State<Float>> { SettledEntry }

/**
 * Reads `Settings.Global.ANIMATOR_DURATION_SCALE` and keeps watching it.
 *
 * Watching matters because the setting is changed from outside the app — developer
 * options, or Accessibility ▸ "Remove animations" — while 주차핀 sits in the background.
 * Reading once at startup would leave a user who just turned animations off watching them
 * until the process restarts.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) {
        mutableStateOf(MotionPolicy.isEnabled(animatorDurationScale(resolver)))
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = MotionPolicy.isEnabled(animatorDurationScale(resolver))
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun animatorDurationScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * The one press effect in the app: a small give under the finger, springing back.
 *
 * Defined once and shared so every button reacts identically — a button that scales a
 * different amount from the one above it is exactly the kind of detail that reads as
 * machine-assembled. Pass the same [InteractionSource] to the control and to this.
 *
 * The scale and the dim are one animation, not two: the alpha is derived from the scale,
 * so they can never drift apart or settle at different moments.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalMotionEnabled.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && motionEnabled) PRESSED_SCALE else 1f,
        // A spring rather than a tween: the release should feel elastic, and this one
        // settles in about 200ms, inside MotionPolicy.MAX_DURATION_MS.
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = (1f - (1f - scale) * PRESSED_DIM_FACTOR).coerceIn(0f, 1f)
    }
}

/**
 * The element's slice of the screen's entry animation: fade up into place, once.
 *
 * [index] is its position down the screen. `ParkingkokScreen` assigns it, so a screen does
 * not have to count its own children.
 *
 * The timeline is read inside the layer block, which means an arriving frame redraws
 * without recomposing, and — more to the point — a `LazyColumn` item that scrolls back
 * into view reads the settled timeline and is simply opaque. Nothing replays on scroll.
 */
@Composable
fun Modifier.entryStagger(index: Int): Modifier {
    val progress = LocalScreenEntry.current
    return graphicsLayer {
        val fraction = FastOutSlowInEasing.transform(
            MotionPolicy.entryFraction(progress.value, index),
        )
        alpha = fraction
        translationY = (1f - fraction) * ENTRY_RISE.toPx()
    }
}

/**
 * Starts this screen's entry timeline, once, and returns it.
 *
 * With motion off the timeline is born settled: `animateFloatAsState` adopts its first
 * target as the starting value, so there is nothing to animate from.
 */
@Composable
fun rememberScreenEntry(): State<Float> {
    val motionEnabled = LocalMotionEnabled.current
    var started by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(
        targetValue = if (started || !motionEnabled) 1f else 0f,
        // Linear: the per-element easing is applied in `entryStagger`, and easing the
        // shared timeline as well would bunch the stagger up at both ends.
        animationSpec = tween(MotionDurations.ENTRY_TOTAL_MS, easing = LinearEasing),
        label = "screenEntry",
    )
    LaunchedEffect(Unit) { started = true }
    return progress
}

/** A timeline that is already over, for previews and any screen that opts out. */
private val SettledEntry: State<Float> = mutableFloatStateOf(1f)

/** How far a control gives under a press. Small enough to feel, too small to jump. */
private const val PRESSED_SCALE = 0.97f

/** Turns the 0.03 of scale into roughly a tenth of alpha. */
private const val PRESSED_DIM_FACTOR = 3f

/** How far an element rises into place on entry. */
private val ENTRY_RISE = 12.dp
