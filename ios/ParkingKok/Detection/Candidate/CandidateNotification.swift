import Foundation
import UserNotifications

/// The names the candidate notification and the tap that comes back have to agree on
/// (docs/04_IOS_IMPLEMENTATION.md §8).
///
/// Its own category, never the diagnostics one: this is product UI about a parking that
/// may be happening right now, and a label prompt about last Tuesday sharing a channel
/// with it would make "the user muted the wrong one" unanswerable.
enum CandidateNotificationAction {
    /// docs/04 §8 spells the category and both actions; the strings are that spelling.
    static let categoryIdentifier = "PARKING_CANDIDATE"
    static let enterFloor = "ENTER_FLOOR"
    static let notParking = "NOT_PARKING"
    static let candidateIdKey = "pk.parking.candidate_id"

    /// One notification per candidate.
    ///
    /// **This is what §10a's deduplication rule is made of.** `UNUserNotificationCenter`
    /// replaces a pending or delivered request that carries an identifier it already has,
    /// so re-posting the same candidate can never stack a second alert, and withdrawing is
    /// a matter of handing the same string back.
    static func requestIdentifier(for candidateId: UUID) -> String {
        "\(categoryIdentifier).\(candidateId.uuidString)"
    }
}

/// What the user reads on the lock screen.
///
/// **Copy is fixed in `docs/02_PRODUCT_SCOPE_AND_FLOWS.md` §5 and must not be reworded.**
/// It is held here as constants rather than inline at the post site so a test can state
/// the contract — including the part that is about what is *absent*: no floor, no address,
/// no coordinate (docs/05 §10a, docs/09 §9). Nothing in this type can express one.
enum CandidateNotificationCopy {
    static let title = "주차한 것 같아요"
    static let body = "마지막으로 확인된 위치와 시간을 저장해뒀어요."
    /// docs/02 §5 "confirm/open floor entry". The inline text action is the platform
    /// enhancement the same section calls optional; the screen behind the body tap is
    /// what makes it correct either way.
    static let enterFloorTitle = "층 입력"
    static let enterFloorButton = "저장"
    static let enterFloorPlaceholder = "예: B3"
    /// docs/10 §7 secondary action, and the word is the product's — not "취소", not "무시".
    static let notParkingTitle = "주차 아님"
}

/// Posting and withdrawing, reduced to what the engine needs.
///
/// A protocol because the decision that matters — post a `medium`, stay silent on a
/// `low`, withdraw the one that was superseded — has to be assertable, and
/// `UNUserNotificationCenter.current()` cannot be reached from a unit test.
///
/// Nothing here throws or reports success. docs/05 §10a: "Notification permission is not
/// required for correctness. Denied, the candidate is saved and surfaces in the app on
/// next launch; nothing is lost and nothing is retried."
protocol CandidateNotifying: Sendable {
    func post(_ candidate: ParkingCandidate) async
    /// Removes both the pending request and the delivered alert for this candidate.
    /// Idempotent, and safe for a candidate that was never posted at all — which is the
    /// ordinary case for `low`.
    func withdraw(candidateId: UUID) async
}

/// Posts through `UNUserNotificationCenter`.
struct UserNotificationCandidateDelivery: CandidateNotifying {
    /// Reached through `current()` on each use rather than stored: the class is not
    /// `Sendable`, and the seam that matters for testing is `CandidateNotifying` itself.
    private var center: UNUserNotificationCenter {
        .current()
    }

    func post(_ candidate: ParkingCandidate) async {
        // §9/§10a: `low` is recorded but never shown. Checked here as well as at the call
        // site so no future caller can post one by forgetting.
        guard candidate.isNotifiable else { return }

        PKNotificationCategories.register()

        let content = UNMutableNotificationContent()
        content.title = CandidateNotificationCopy.title
        content.body = CandidateNotificationCopy.body
        content.categoryIdentifier = CandidateNotificationAction.categoryIdentifier
        // The candidate id is the only payload. A UUID, never a place.
        content.userInfo = [CandidateNotificationAction.candidateIdKey: candidate.id.uuidString]
        // The user has just walked away from a car and has 45 minutes to answer; this is
        // worth breaking a Focus for, and it is the one notification the app posts that is
        // about something happening now.
        content.interruptionLevel = .timeSensitive

        let request = UNNotificationRequest(
            identifier: CandidateNotificationAction.requestIdentifier(for: candidate.id),
            content: content,
            trigger: nil
        )
        do {
            try await center.add(request)
        } catch {
            // Best-effort by contract: the candidate is already on disk and the app shows
            // it on next launch.
            let nsError = error as NSError
            AppLog.detection.error(
                "candidate notification failed: \(nsError.domain, privacy: .public)(\(nsError.code, privacy: .public))"
            )
        }
    }

    func withdraw(candidateId: UUID) async {
        let identifier = CandidateNotificationAction.requestIdentifier(for: candidateId)
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }
}

/// The app's whole set of notification categories, registered in one call.
///
/// `setNotificationCategories` **replaces** the set rather than adding to it, so two
/// features each registering their own is a bug that costs whichever ran first its
/// actions. The trace label prompt owned that call while it was the only category; this
/// is the single registration point its comment said would be needed.
enum PKNotificationCategories {
    static func register() {
        UNUserNotificationCenter.current().setNotificationCategories([
            candidate,
            TraceLabelPromptCategory.category
        ])
    }

    /// docs/04 §8: text input `ENTER_FLOOR`, destructive-ish `NOT_PARKING`.
    ///
    /// `ENTER_FLOOR` is **not** `.foreground`: docs/02 §5 wants the floor enterable where
    /// the user already is, and the response handler writes the record directly. The body
    /// tap is what opens the confirmation screen.
    private static var candidate: UNNotificationCategory {
        let enterFloor = UNTextInputNotificationAction(
            identifier: CandidateNotificationAction.enterFloor,
            title: CandidateNotificationCopy.enterFloorTitle,
            options: [],
            textInputButtonTitle: CandidateNotificationCopy.enterFloorButton,
            textInputPlaceholder: CandidateNotificationCopy.enterFloorPlaceholder
        )
        let notParking = UNNotificationAction(
            identifier: CandidateNotificationAction.notParking,
            title: CandidateNotificationCopy.notParkingTitle,
            // Destructive in the OS sense — it throws the guess away — but it is the
            // honest answer, so it is offered at the same weight as confirming.
            options: [.destructive]
        )
        return UNNotificationCategory(
            identifier: CandidateNotificationAction.categoryIdentifier,
            actions: [enterFloor, notParking],
            intentIdentifiers: [],
            options: []
        )
    }
}
