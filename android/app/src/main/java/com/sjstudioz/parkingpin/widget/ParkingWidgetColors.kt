package com.sjstudioz.parkingpin.widget

import androidx.glance.material3.ColorProviders
import com.sjstudioz.parkingpin.theme.DarkColors
import com.sjstudioz.parkingpin.theme.LightColors

/**
 * The app's docs/10_DESIGN_UX_SPEC.md §2 tokens, handed to Glance.
 *
 * Built from the very schemes `ParkingpinTheme` uses rather than from a second set of
 * literals: §2 allows colour values in one file only, and a widget that drifted from the
 * app would read as a different product on the same home screen.
 *
 * Glance resolves light and dark itself — a widget cannot observe `isSystemInDarkTheme`,
 * because the host draws it and the answer can change without the app running.
 */
internal val ParkingpinGlanceColors = ColorProviders(light = LightColors, dark = DarkColors)
