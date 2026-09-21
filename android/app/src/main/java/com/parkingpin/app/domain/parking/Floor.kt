package com.parkingpin.app.domain.parking

/**
 * How a floor string was understood (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §2 `floorKind`).
 *
 * The name is part of the cross-platform record contract, so it is persisted as the enum
 * name and iOS must agree on the spelling.
 */
enum class FloorKind {
    /** 지하 — `B1`..`Bn`. */
    BASEMENT,

    /** 지상 — `1F`..`nF`. */
    GROUND,

    /** Anything the parser could not place on the numeric ladder; kept verbatim. */
    FREE_TEXT,
}

/**
 * A parked floor: what the user typed, plus the numeric reading when there is one
 * (docs/01_PRODUCT_REQUIREMENTS.md FR-005).
 *
 * [raw] is always preserved, even when [kind] is [FloorKind.FREE_TEXT], because it is the
 * only thing the user will recognise when they come back to the car. [number] is a
 * magnitude, never signed — the sign lives in [kind]; see [signedIndex] for the ordering
 * used by the `-`/`+` keys.
 */
data class Floor(
    val raw: String,
    val kind: FloorKind,
    val number: Int?,
) {
    init {
        require(kind == FloorKind.FREE_TEXT || number != null) {
            "a numeric floor kind must carry a number"
        }
    }

    /**
     * Whether the `-`/`+` keys apply. FR-005: "numeric parseable floor만 widget +/- 지원".
     */
    val isSteppable: Boolean get() = kind != FloorKind.FREE_TEXT

    /**
     * Position on a single ladder where B1 is -1 and 1F is +1.
     *
     * There is no 0층 in Korean buildings, so the ladder has no zero and stepping up from
     * B1 lands on 1F. Null when the floor is free text and therefore unordered.
     */
    val signedIndex: Int?
        get() = when (kind) {
            FloorKind.BASEMENT -> number?.let { -it }
            FloorKind.GROUND -> number
            FloorKind.FREE_TEXT -> null
        }

    /**
     * The hero string: `B3`, `3F`, or the raw text.
     *
     * Deliberately not localised. `B3`/`3F` are how the signage in a Korean garage is
     * actually painted, and docs/10_DESIGN_UX_SPEC.md §6 wants this read at a glance.
     */
    val displayLabel: String
        get() = when (kind) {
            FloorKind.BASEMENT -> "B$number"
            FloorKind.GROUND -> "${number}F"
            FloorKind.FREE_TEXT -> raw
        }

    /**
     * What TalkBack should say. docs/10_DESIGN_UX_SPEC.md §12 gives `지하 3층` as the
     * reference phrasing — "B3" is read letter-by-letter otherwise.
     */
    val spokenLabel: String
        get() = when (kind) {
            FloorKind.BASEMENT -> "지하 ${number}층"
            FloorKind.GROUND -> "지상 ${number}층"
            FloorKind.FREE_TEXT -> raw
        }
}
