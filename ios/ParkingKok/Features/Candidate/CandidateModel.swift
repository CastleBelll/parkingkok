import Foundation
import Observation

/// How answering the guess gets back to the state machine (docs/05 §3a:
/// `CANDIDATE_PENDING → PARKED` on confirm, `→ IDLE` on reject or expiry).
///
/// A protocol rather than a reach for `DetectionRuntime.shared`, so the screen and its
/// tests never need a Core Location stack to exist.
@MainActor
protocol CandidateResolving: AnyObject {
    func resolveCandidate(_ outcome: CandidateOutcome) async
}

/// How a pending candidate stopped being pending.
///
/// `Codable` because docs/10 §7b's history outlives the process that wrote it. The raw
/// values are the stored shape, so renaming a case is a schema change — see
/// `FileCandidateHistoryStore.schemaVersion`.
enum CandidateOutcome: String, Sendable, Equatable, Codable {
    case confirmed
    case rejected
    case expired
}

/// Application state for the one pending candidate: the screen, the home row and the
/// notification actions all go through here.
///
/// ### Expiry is lazy, and that is deliberate
/// Nothing schedules a timer for the 45 minutes (docs/05 §10). A wake costs battery, and
/// the product's whole claim is that detection is cheap; so the deadline is evaluated
/// whenever anyone looks — app activation, a refresh, a notification response. The visible
/// consequence is that an expired notification can still be sitting on the lock screen: a
/// tap on it lands on home, withdraws it, and creates nothing, which is exactly what §10a
/// asks for and is why "the app does not apologise for it in a dialog" is possible at all.
@MainActor
@Observable
final class CandidateModel {
    /// The candidate the user has not answered yet, already checked against its own
    /// expiry. `nil` is the ordinary state.
    private(set) var pending: ParkingCandidate?
    /// Where a notification tap wants the user taken. `RootView` consumes it and sets it
    /// back to `nil`; it is held rather than pushed directly because the tap can arrive
    /// before there is a navigation stack to push onto.
    var pendingNavigation: AppRoute?

    private let store: any ParkingCandidateStoring
    /// docs/10 §7b's bell. Written at every resolution, read by the list — see
    /// `retire`, which is the one place all three outcomes pass through.
    private let history: any CandidateHistoryStoring
    private let notifier: any CandidateNotifying
    private let analytics: any AnalyticsRecording
    private let parking: ParkingModel
    private let clock: any DateProviding
    /// `nil` in previews and in tests that are only about the screen.
    private weak var resolver: (any CandidateResolving)?
    /// The in-flight tail of the last answer: withdrawing the notification and moving the
    /// state machine, both of which are `async`.
    ///
    /// Held so a test can await what a screen never has to. The alternative was making
    /// `confirm` and `reject` asynchronous for every caller, including a notification
    /// response that docs/04 §8 wants finished quickly.
    private(set) var retirement: Task<Void, Never>?

    init(
        store: any ParkingCandidateStoring,
        history: any CandidateHistoryStoring = UnavailableCandidateHistoryStore(),
        notifier: any CandidateNotifying = UserNotificationCandidateDelivery(),
        analytics: any AnalyticsRecording = DisabledAnalyticsRecorder(),
        parking: ParkingModel,
        clock: any DateProviding = SystemDateProvider(),
        resolver: (any CandidateResolving)? = nil
    ) {
        self.store = store
        self.history = history
        self.notifier = notifier
        self.analytics = analytics
        self.parking = parking
        self.clock = clock
        self.resolver = resolver
    }

    /// §7a's three quick picks, from what this user has saved before.
    var floorPicks: [FloorValue] {
        CandidateFloorPicks.picks(from: allSessions)
    }

    /// docs/10 §7b: "The bell carries a small dot while a candidate is unanswered, and
    /// only then." A dot, not a count — §12 allows at most one candidate, so there is
    /// never a number to show.
    var hasUnansweredCandidate: Bool {
        pending != nil
    }

    /// docs/10 §7b's list, resolved against the records its rows point at.
    ///
    /// A function and not a property: it reads the history file, and docs/16 §5 keeps
    /// storage work out of `body`. Screens call it from `onAppear` like every other read
    /// in the app.
    func notificationHistory() -> [NotificationHistoryItem] {
        NotificationHistoryItem.list(
            pending: pending,
            entries: history.entries(),
            sessions: allSessions
        )
    }

    /// Every record the store holds, the active one included. A confirmed candidate
    /// becomes the *active* parking, so anything reading "what has this user saved"
    /// misses the newest answer without it.
    private var allSessions: [ParkingSession] {
        var sessions = parking.completedSessions
        if let active = parking.activeSession {
            sessions.append(active)
        }
        return sessions
    }

    /// Re-reads the candidate and retires it if its 45 minutes are up.
    ///
    /// Safe to call on every activation: it touches one small file and, in the overwhelming
    /// case of no candidate, does nothing else.
    func refresh() {
        guard let candidate = store.load() else {
            pending = nil
            return
        }
        guard !candidate.isExpired(now: clock.now) else {
            pending = nil
            expire(candidate)
            return
        }
        pending = candidate
    }

    /// The candidate a notification tap or a route is about, or `nil` once it is gone.
    func candidate(id: UUID) -> ParkingCandidate? {
        refresh()
        guard let pending, pending.id == id else { return nil }
        return pending
    }

    /// One tap on a quick pick (docs/10 §7a: "Choosing a floor confirms in one tap").
    @discardableResult
    func confirm(_ candidate: ParkingCandidate, floor: FloorValue) -> Bool {
        confirm(candidate, draft: ManualParkingDraft(floorText: floor.raw))
    }

    /// docs/05 §10a: writes the parking record and reports the confirmation.
    ///
    /// @return whether the record was written. `false` leaves the candidate pending, so a
    /// store failure costs the user nothing but a second tap.
    @discardableResult
    func confirm(_ candidate: ParkingCandidate, draft: ManualParkingDraft) -> Bool {
        guard let recordId = parking.saveDetectedParking(from: candidate, draft: draft) else {
            return false
        }
        // The event goes out after the write, never before: a confirmation that failed to
        // save would otherwise be counted as precision the detector does not have.
        analytics.record(.parkingCandidateConfirmed(candidate.analyticsProperties))
        // §10a: the record id is what makes the `저장됨` row openable and what lets it
        // show a floor this entry is forbidden to store itself.
        retire(candidate, outcome: .confirmed, recordId: recordId)
        return true
    }

    /// docs/05 §10a: the candidate is discarded and the rejection is reported.
    ///
    /// **The event is never dropped.** It is the strongest signal the detector gets and
    /// the one that pays for the whole feature, so it is recorded before anything that
    /// could fail — the store clear and the withdrawal are both best-effort, and neither
    /// is allowed to cost the reason a false positive happened.
    func reject(_ candidate: ParkingCandidate) {
        analytics.record(.parkingCandidateRejected(candidate.analyticsProperties))
        retire(candidate, outcome: .rejected)
    }

    /// Where a notification tap should land (docs/05 §10a "What a tap does").
    ///
    /// A candidate that is still pending opens its confirmation screen. One that has
    /// expired or been answered opens the record it became — the active detected parking —
    /// and otherwise nothing at all, which leaves the user on home.
    func navigation(forCandidateId id: UUID) -> AppRoute? {
        if candidate(id: id) != nil {
            return .candidateConfirmation(id: id)
        }
        if let active = parking.activeSession, active.source == .detected {
            return .parkingDetail(id: active.id)
        }
        return nil
    }

    /// docs/05 §10: the notification is withdrawn, no record is created, and nothing is
    /// reported — docs/17 §2 has no event for a guess nobody answered.
    private func expire(_ candidate: ParkingCandidate) {
        retire(candidate, outcome: .expired)
    }

    /// The one place all three of §7b's outcomes pass through, which is why the history
    /// append lives here rather than at each caller.
    private func retire(_ candidate: ParkingCandidate, outcome: CandidateOutcome, recordId: UUID? = nil) {
        pending = nil
        history.append(CandidateHistoryEntry(candidate: candidate, outcome: outcome, recordId: recordId))
        do {
            try store.clear()
        } catch {
            // The candidate is already gone from the UI and `refresh` re-checks expiry, so
            // a file that would not delete costs a stale read at worst.
            AppLog.detection.notice("candidate clear failed: \(String(describing: error), privacy: .public)")
        }
        let notifier = notifier
        let resolver = resolver
        retirement = Task {
            await notifier.withdraw(candidateId: candidate.id)
            await resolver?.resolveCandidate(outcome)
        }
    }
}
