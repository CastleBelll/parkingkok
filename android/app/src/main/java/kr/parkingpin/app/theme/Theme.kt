package kr.parkingpin.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import kr.parkingpin.app.ui.motion.LocalMotionEnabled
import kr.parkingpin.app.ui.motion.rememberMotionEnabled

/**
 * docs/10_DESIGN_UX_SPEC.md §2 tokens mapped onto Material 3 roles.
 *
 * There is no `dynamicColor` switch. §3 states that dynamic colour "is **not** allowed to
 * replace core 주차핀 brand colours automatically"; a parameter defaulting to `true` — which
 * is what the Compose project template ships and what this file used to hold — is exactly
 * that prohibited behaviour, so the capability is gone rather than defaulted off.
 *
 * Mapping (§2 name -> Material role):
 * - background   -> background
 * - surface      -> surface, surfaceContainer*
 * - textPrimary  -> onBackground / onSurface
 * - textSecondary-> onSurfaceVariant
 * - primary      -> primary
 * - accent       -> tertiary
 * - divider      -> outlineVariant
 * - danger       -> error
 *
 * The two schemes are `internal` rather than private because the Glance widget renders
 * outside `MaterialTheme` and must read the same tokens — see `ParkingpinGlanceColors`.
 * A second palette declared for the widget is exactly the scattering §2 forbids.
 */
internal val LightColors = lightColorScheme(
    primary = BrandPalette.LightPrimary,
    onPrimary = BrandPalette.OnPrimary,
    primaryContainer = BrandPalette.LightPrimaryContainer,
    onPrimaryContainer = BrandPalette.LightOnPrimaryContainer,
    secondary = BrandPalette.LightPrimary,
    onSecondary = BrandPalette.OnPrimary,
    secondaryContainer = BrandPalette.LightPrimaryContainer,
    onSecondaryContainer = BrandPalette.LightOnPrimaryContainer,
    tertiary = BrandPalette.LightAccent,
    onTertiary = BrandPalette.OnPrimary,
    tertiaryContainer = BrandPalette.LightAccentContainer,
    onTertiaryContainer = BrandPalette.LightOnAccentContainer,
    background = BrandPalette.LightBackground,
    onBackground = BrandPalette.LightTextPrimary,
    surface = BrandPalette.LightSurface,
    onSurface = BrandPalette.LightTextPrimary,
    surfaceVariant = BrandPalette.LightSurfaceVariant,
    onSurfaceVariant = BrandPalette.LightTextSecondary,
    surfaceContainerLowest = BrandPalette.LightSurface,
    surfaceContainerLow = BrandPalette.LightSurface,
    surfaceContainer = BrandPalette.LightSurface,
    surfaceContainerHigh = BrandPalette.LightSurfaceVariant,
    surfaceContainerHighest = BrandPalette.LightSurfaceVariant,
    outline = BrandPalette.LightTextSecondary,
    outlineVariant = BrandPalette.LightDivider,
    error = BrandPalette.LightDanger,
    onError = BrandPalette.OnPrimary,
    errorContainer = BrandPalette.LightDangerContainer,
    onErrorContainer = BrandPalette.LightOnDangerContainer,
)

internal val DarkColors = darkColorScheme(
    primary = BrandPalette.DarkPrimary,
    onPrimary = BrandPalette.OnPrimary,
    primaryContainer = BrandPalette.DarkPrimaryContainer,
    onPrimaryContainer = BrandPalette.DarkOnPrimaryContainer,
    secondary = BrandPalette.DarkPrimary,
    onSecondary = BrandPalette.OnPrimary,
    secondaryContainer = BrandPalette.DarkPrimaryContainer,
    onSecondaryContainer = BrandPalette.DarkOnPrimaryContainer,
    tertiary = BrandPalette.DarkAccent,
    onTertiary = BrandPalette.DarkBackground,
    tertiaryContainer = BrandPalette.DarkAccentContainer,
    onTertiaryContainer = BrandPalette.DarkOnAccentContainer,
    background = BrandPalette.DarkBackground,
    onBackground = BrandPalette.DarkTextPrimary,
    surface = BrandPalette.DarkSurface,
    onSurface = BrandPalette.DarkTextPrimary,
    surfaceVariant = BrandPalette.DarkSurfaceVariant,
    onSurfaceVariant = BrandPalette.DarkTextSecondary,
    surfaceContainerLowest = BrandPalette.DarkBackground,
    surfaceContainerLow = BrandPalette.DarkSurface,
    surfaceContainer = BrandPalette.DarkSurface,
    surfaceContainerHigh = BrandPalette.DarkSurfaceVariant,
    surfaceContainerHighest = BrandPalette.DarkSurfaceVariant,
    outline = BrandPalette.DarkTextSecondary,
    outlineVariant = BrandPalette.DarkDivider,
    error = BrandPalette.DarkDanger,
    onError = BrandPalette.DarkBackground,
    errorContainer = BrandPalette.DarkDangerContainer,
    onErrorContainer = BrandPalette.DarkOnDangerContainer,
)

@Composable
fun ParkingpinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalParkingpinSpacing provides ParkingpinSpacing(),
        LocalParkingpinSurfaces provides ParkingpinSurfaces(
            border = if (darkTheme) BrandPalette.DarkDivider else BrandPalette.LightDivider,
        ),
        // Read once, here, so that every animation below this point obeys the same answer
        // and no screen has to remember to ask (docs/01_PRODUCT_REQUIREMENTS.md §8).
        LocalMotionEnabled provides rememberMotionEnabled(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ParkingpinTypography,
            shapes = ParkingpinShapes,
            content = content,
        )
    }
}

/** Spacing tokens, reached the same way as `MaterialTheme.colorScheme`. */
val MaterialTheme.spacing: ParkingpinSpacing
    @Composable @ReadOnlyComposable get() = LocalParkingpinSpacing.current

/** Surface tokens, reached the same way as `MaterialTheme.colorScheme`. */
val MaterialTheme.surfaces: ParkingpinSurfaces
    @Composable @ReadOnlyComposable get() = LocalParkingpinSurfaces.current
