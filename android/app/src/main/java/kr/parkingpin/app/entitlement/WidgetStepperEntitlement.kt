package kr.parkingpin.app.entitlement

/**
 * Whether interactive floor stepping on the widget is available
 * (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a "Entitlement", docs/04_ANDROID_IMPLEMENTATION.md §10).
 *
 * Stepping from the widget is a Plus feature, so this names the feature and
 * [plusEntitlement] answers whether Plus. It used to carry its own copy of the debuggable
 * rule; that copy moved down so the next Plus feature does not make a third one.
 *
 * Nothing else in the app may decide this. `AppContainer` is the single caller and hands the
 * answer to the widget through `ParkingWidgetProjection.stepperEntitled`.
 *
 * @param debuggable true for the DEV and STAGING builds, false for the PROD release build.
 */
fun isWidgetStepperEntitled(debuggable: Boolean): Boolean =
    plusEntitlement(debuggable).allowsPlusFeatures
