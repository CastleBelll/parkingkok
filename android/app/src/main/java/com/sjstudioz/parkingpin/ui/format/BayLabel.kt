package com.sjstudioz.parkingpin.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sjstudioz.parkingpin.R

/**
 * The bay with the word that says what it is — unless the user already wrote it.
 *
 * A bare number under the floor at hero weight says nothing about what the number is, so
 * 주차 번호 alone takes the word. But FR-006 lets the field hold any text, and someone
 * copying a wall writes `01번` as often as `01`: appending unconditionally rendered that
 * as `01번번`. Mirrors iOS's `ParkingSession.placeText`.
 */
@Composable
fun bayLabel(spot: String): String =
    if (spot.endsWith(BAY_WORD)) spot else stringResource(R.string.home_spot_only, spot)

private const val BAY_WORD = "번"
