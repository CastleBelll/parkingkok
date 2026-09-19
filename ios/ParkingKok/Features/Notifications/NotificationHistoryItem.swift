import Foundation

/// One row of docs/10 §7b's list, resolved against the records it points at.
///
/// A value, built by a pure function, because everything §7b fixes about this screen is a
/// rule about rows — what the second line says, which row opens something, which row must
/// not look tappable — and none of it should need a view to be asserted.
struct NotificationHistoryItem: Identifiable, Equatable, Sendable {
    /// §7b's three outcomes, plus the one candidate that has no outcome yet.
    ///
    /// The list is closed on purpose: "Three outcomes and no others."
    enum Kind: Equatable, Sendable {
        /// Raised and still unanswered. Not a history entry — it is the live candidate
        /// slot (docs/05 §10a), shown here because §7b's tap rules describe it.
        case pending
        /// §7b `저장됨`. `place` is the floor and spot read off the record it became;
        /// `nil` when that record has since been deleted, which leaves the outcome true
        /// and nothing left to name.
        case saved(place: String?)
        case rejected
        case expired
    }

    /// The `candidateId`, in both the pending and the resolved case.
    let id: UUID
    /// When the phone buzzed — §7b's right-hand column.
    let raisedAt: Date
    let kind: Kind
    /// Where a tap goes, or `nil` for a row that does nothing.
    ///
    /// §7b: "One that was rejected or expired does nothing — it is history, and there is
    /// nothing left to act on. A row that does nothing must not look tappable." So this
    /// being `nil` is what the row draws itself from, not a second flag that could
    /// disagree with it.
    let destination: AppRoute?

    /// §7b's second line, verbatim where the spec gives it words.
    var detail: String {
        switch kind {
        // Fixed with the coordinator: §7b names the second line for the three resolved
        // outcomes and is silent on the unanswered one, and this is the wording both
        // platforms use.
        case .pending: "확인이 필요해요"
        case let .saved(place): place.map { "\($0) 로 저장됨" } ?? "저장됨"
        case .rejected: CandidateNotificationCopy.notParkingTitle
        case .expired: "응답 없음"
        }
    }

    var isTappable: Bool {
        destination != nil
    }

    /// One announcement per row — the title is the same on every row, so what tells them
    /// apart is the outcome and the time (docs/10 §12).
    func accessibilityText(now: Date) -> String {
        "\(CandidateNotificationCopy.title), \(detail), "
            + ParkingDateText.dayAndTime(raisedAt, now: now)
    }
}

extension NotificationHistoryItem {
    /// docs/10 §7b's list: the unanswered candidate, then the last 30 resolutions, newest
    /// first.
    ///
    /// `sessions` is every record the store holds, active included — a confirmation
    /// becomes the *active* parking, so a list built from finished records alone would
    /// show `저장됨` with nothing to open on the one row the user just created.
    static func list(
        pending: ParkingCandidate?,
        entries: [CandidateHistoryEntry],
        sessions: [ParkingSession]
    ) -> [NotificationHistoryItem] {
        let recordIds = Set(sessions.map(\.id))
        var items = entries.map { entry in
            NotificationHistoryItem(
                id: entry.id,
                raisedAt: entry.raisedAt,
                kind: kind(for: entry, sessions: sessions),
                destination: entry.recordId
                    .flatMap { recordIds.contains($0) ? AppRoute.parkingDetail(id: $0) : nil }
            )
        }
        if let pending {
            items.append(
                NotificationHistoryItem(
                    id: pending.id,
                    raisedAt: pending.detectedAt,
                    kind: .pending,
                    destination: .candidateConfirmation(id: pending.id)
                )
            )
        }
        // The pending candidate is newer than every resolution by construction (§12 allows
        // one at a time), but sorting says so rather than assuming it.
        return items.sorted { $0.raisedAt > $1.raisedAt }
    }

    private static func kind(
        for entry: CandidateHistoryEntry,
        sessions: [ParkingSession]
    ) -> Kind {
        switch entry.outcome {
        case .confirmed:
            let record = entry.recordId.flatMap { id in sessions.first { $0.id == id } }
            return .saved(place: record.flatMap(placeText(for:)))
        case .rejected:
            return .rejected
        case .expired:
            return .expired
        }
    }

    /// §7b's `B3 · A구역 142`. Read from the record every time, because the entry itself
    /// is forbidden to store a floor (docs/05 §10a) — which also means an edited floor is
    /// right here without the history having to be rewritten.
    private static func placeText(for session: ParkingSession) -> String? {
        let parts = [session.floor?.displayText, session.placeText].compactMap(\.self)
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}
