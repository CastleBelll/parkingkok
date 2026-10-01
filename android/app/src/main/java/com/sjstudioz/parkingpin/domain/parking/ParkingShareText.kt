package com.sjstudioz.parkingpin.domain.parking

import com.sjstudioz.parkingpin.domain.detection.ParkingEndProposalNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `위치 보내기` — the text the share sheet carries (docs/01 §5a "주차 위치를 가족에게 보내기",
 * DECIDED 2026-10-01). iOS `ParkingShareText` produces the same three lines for the same
 * record.
 *
 * The user sends it, to whom they choose, through their own app; nothing is stored or
 * uploaded (docs/06 §1a). Memo and photo stay behind: the memo is a note to self, and a
 * photo carries EXIF.
 */
object ParkingShareText {

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

    /**
     * ```
     * B3 · A구역 · 142에 주차했어요
     * 10월 1일 오후 2:35
     * 마지막으로 확인된 위치: https://www.google.com/maps/search/?api=1&query=37.49790,127.02760
     * ```
     * The place line is the home hero's (`ParkingEndProposalNotice.placeText`); the map line
     * is left out when the record has no location (FR-001). Google Maps' cross-platform URL,
     * so a recipient on either OS gets a pin. Five decimals is about a metre — more than
     * the fix ever knew.
     */
    fun text(record: ParkingRecord, zone: ZoneId): String = buildList {
        add(ParkingEndProposalNotice.placeText(record)?.let { "${it}에 주차했어요" } ?: "주차했어요")
        add(timeFormat.format(Instant.ofEpochMilli(record.startedAtMillis).atZone(zone)))
        record.location?.let { add("마지막으로 확인된 위치: ${mapUrl(it)}") }
    }.joinToString("\n")

    fun mapUrl(location: ParkingLocation): String =
        "https://www.google.com/maps/search/?api=1&query=" +
            String.format(Locale.ROOT, "%.5f,%.5f", location.latitude, location.longitude)
}
