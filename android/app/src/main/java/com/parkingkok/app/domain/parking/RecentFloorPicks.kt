package com.parkingkok.app.domain.parking

/**
 * The floors offered as one-tap choices on the confirmation screen
 * (docs/10_DESIGN_UX_SPEC.md §7a "The floor choices").
 *
 * > The picks come from **the floors this user has saved before**, most recent first —
 * > local history, no network, no guessing. A user who always parks on B3 sees B3.
 *
 * Which makes the shape of this function the whole feature: no defaults, no `B1 B2 B3`
 * fallback, no frequency model. A floor the user has never typed never appears, because
 * offering one would be the app guessing — and §7a's opening line is that this screen must
 * not guess. With fewer than [MAX_PICKS] past floors the row simply shows fewer, and a
 * first-ever run shows none, leaving only `직접 입력`.
 */
object RecentFloorPicks {

    /** §7a: "three quick picks and 직접 입력". */
    const val MAX_PICKS: Int = 3

    /**
     * Picks from [storedFloorRaws], which the caller supplies newest first.
     *
     * De-duplicated by what the user would *read* rather than by the raw string, so a
     * history of `b3`, `B3`, `지하3층` offers one button and not three. The first spelling
     * wins, because it is the most recent one and therefore the one the user last chose.
     *
     * Unparseable text is kept: FR-005 says free text is a legitimate floor, and a user
     * who parks in `임원동` deserves that button as much as anyone gets `B3`.
     */
    fun of(storedFloorRaws: List<String>, limit: Int = MAX_PICKS): List<Floor> {
        if (limit <= 0) return emptyList()
        val picks = LinkedHashMap<String, Floor>()
        for (raw in storedFloorRaws) {
            val floor = FloorParser.parse(raw) ?: continue
            picks.putIfAbsent(floor.displayLabel, floor)
            if (picks.size == limit) break
        }
        return picks.values.toList()
    }
}
