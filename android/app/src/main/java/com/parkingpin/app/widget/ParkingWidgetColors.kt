package com.parkingpin.app.widget

import androidx.glance.material3.ColorProviders
import com.parkingpin.app.theme.DarkColors
import com.parkingpin.app.theme.LightColors

/**
 * The app's docs/10_DESIGN_UX_SPEC.md §2 tokens, handed to Glance.
 *
 * Built from the very schemes `ParkingkokTheme` uses rather than from a second set of
 * literals: §2 allows colour values in one file only, and a widget that drifted from the
 * app would read as a different product on the same home screen.
 *
 * Glance resolves light and dark itself — a widget cannot observe `isSystemInDarkTheme`,
 * because the host draws it and the answer can change without the app running.
 */
internal val ParkingkokGlanceColors = ColorProviders(light = LightColors, dark = DarkColors)
