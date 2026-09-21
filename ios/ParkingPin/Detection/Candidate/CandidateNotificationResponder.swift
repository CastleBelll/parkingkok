import Foundation
import UserNotifications

/// Turns a tapped candidate notification into the one thing it meant.
///
/// Thin on purpose: every outcome is a `CandidateModel` call, so the lock screen and the
/// confirmation screen cannot drift into answering the same question differently. The
/// process may have been launched for this response, which is why nothing here draws,
/// waits or asks a permission.
@MainActor
final class CandidateNotificationResponder: NotificationResponding {
    let categoryIdentifier = CandidateNotificationAction.categoryIdentifier

    private let model: CandidateModel

    init(model: CandidateModel) {
        self.model = model
    }

    func handle(actionIdentifier: String, userInfo: [String: String]) {
        guard let candidateId = userInfo[CandidateNotificationAction.candidateIdKey]
            .flatMap(UUID.init(uuidString:))
        else {
            return
        }

        switch actionIdentifier {
        case CandidateNotificationAction.notParking:
            // The one action worth launching a process for. A candidate that is already
            // gone is not an error — the user answered on the screen a moment ago.
            guard let candidate = model.candidate(id: candidateId) else { return }
            model.reject(candidate)

        case CandidateNotificationAction.enterFloor:
            guard let candidate = model.candidate(id: candidateId) else { return }
            // Empty or unparseable text still confirms: the user said "yes, I parked",
            // and FR-006 makes the floor optional. `FloorValue.parse` keeps whatever they
            // typed, so `주차타워 2` survives as well as `B3`.
            let text = userInfo[PKNotificationRouter.userTextKey] ?? ""
            model.confirm(candidate, draft: ManualParkingDraft(floorText: text))

        case UNNotificationDefaultActionIdentifier:
            // docs/05 §10a: a tap opens the confirmation screen, and never silently
            // creates a parking. An expired one lands on home.
            model.pendingNavigation = model.navigation(forCandidateId: candidateId)

        default:
            // A swipe-away, or an identifier from a build that offered different actions.
            // The candidate stays pending until it expires — dismissing a notification is
            // not an answer, and treating it as one would throw away the trip.
            break
        }
    }
}
