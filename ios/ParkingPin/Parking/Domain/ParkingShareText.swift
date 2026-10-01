import Foundation

/// `위치 보내기` — the text the share sheet carries (docs/01 §5a "주차 위치를 가족에게 보내기",
/// DECIDED 2026-10-01). Android `ParkingShareText` produces the same three lines for the
/// same record.
///
/// The user sends it, to whom they choose, through their own app; nothing is stored or
/// uploaded (docs/06 §1a). Memo and photo stay behind: the memo is a note to self, and a
/// photo carries EXIF.
enum ParkingShareText {
    /// ```
    /// B3 · A구역 · 142에 주차했어요
    /// 10월 1일 오후 2:35
    /// 마지막으로 확인된 위치: https://www.google.com/maps/search/?api=1&query=37.49790,127.02760
    /// ```
    /// The place line is the home hero's (`ParkingEndProposalCopy.placeText`); the map line
    /// is left out when the record has no location (FR-001). Google Maps' cross-platform URL,
    /// so a recipient on either OS gets a pin. Five decimals is about a metre — more than
    /// the fix ever knew.
    static func text(for session: ParkingSession, timeZone: TimeZone = .current) -> String {
        let place = ParkingEndProposalCopy.placeText(floor: session.floor, zone: session.zone, spot: session.spot)
        var lines = [place.map { "\($0)에 주차했어요" } ?? "주차했어요", timeText(session.startedAt, timeZone: timeZone)]
        if let location = session.location {
            lines.append("마지막으로 확인된 위치: \(mapURL(latitude: location.latitude, longitude: location.longitude))")
        }
        return lines.joined(separator: "\n")
    }

    static func mapURL(latitude: Double, longitude: Double) -> String {
        // POSIX, so a comma-decimal locale cannot break the URL.
        let coordinate = String(format: "%.5f,%.5f", locale: Locale(identifier: "en_US_POSIX"), latitude, longitude)
        return "https://www.google.com/maps/search/?api=1&query=\(coordinate)"
    }

    private static func timeText(_ date: Date, timeZone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = timeZone
        formatter.dateFormat = "M월 d일 a h:mm"
        return formatter.string(from: date)
    }
}
