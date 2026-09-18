package com.parkingkok.app.entitlement

/**
 * Whether interactive floor stepping on the widget is available
 * (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a "Entitlement", docs/04_ANDROID_IMPLEMENTATION.md §10).
 *
 * Stepping from the widget is a Plus feature, and Plus does not exist until M6. §7a fixes
 * the interim: one hardcoded source, one function, one file per platform, true on
 * DEV/STAGING and false on PROD, so the interactive path is fully built and tested now
 * while a shipped build stays read-only. M6 replaces the body of this function and
 * nothing else.
 *
 * The parameter is the build's own debuggable flag rather than a [android.content.Context],
 * so both branches are reachable from a plain JVM test — §7a requires both to be covered,
 * and a function that could only be exercised on a debuggable device could not be.
 *
 * Nothing else in the app may decide this. `AppContainer` is the single caller and hands
 * the answer to the widget through `ParkingWidgetProjection.stepperEntitled`.
 *
 * @param debuggable true for the DEV and STAGING builds, false for the PROD release build.
 */
fun isWidgetStepperEntitled(debuggable: Boolean): Boolean = debuggable
