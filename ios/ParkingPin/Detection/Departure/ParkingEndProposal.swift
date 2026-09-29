import Foundation
import UserNotifications

/// A departure the engine noticed and the user has not answered yet (docs/05 §11a,
/// DECIDED BY THE USER 2026-09-29: ask, never end silently).
///
/// **At most one.** A later departure from the same parking replaces it, so a single slot is
/// the whole shape — and a single slot is what keeps two prompts off the lock screen.
struct ParkingEndProposal: Codable, Sendable, Equatable {
    /// The record the question is about. `nil` only when the proposal was raised without a
    /// projection to read the id from; it then applies to whichever parking is active.
    let sessionId: UUID?
    /// `ProposeParkingEnd`'s stamp: when the car pulled away. The end the user accepts.
    let departedAt: Date
    /// When the prompt was raised. Diagnostics only.
    let proposedAt: Date

    /// Whether this still asks about `session`. A proposal about a record that was ended or
    /// replaced meanwhile is stale and must be withdrawn rather than applied to its successor.
    func isAbout(_ session: ParkingSession) -> Bool {
        sessionId == nil || sessionId == session.id
    }

    /// The end to stamp on `session`: never before it started, however the clocks drifted.
    func endedAt(for session: ParkingSession) -> Date {
        max(departedAt, session.startedAt)
    }
}

/// What the background wake knows about the active parking without opening SwiftData
/// (docs/04 §7): the widget projection's id, start and place.
struct ActiveParkingSummary: Sendable, Equatable {
    let sessionId: UUID
    let startedAt: Date
    /// `B3 · A구역 · 142`, or whatever part of it exists; `nil` when nothing was recorded.
    let placeText: String?

    init(sessionId: UUID, startedAt: Date, floor: FloorValue?, zone: String?, spot: String?) {
        self.sessionId = sessionId
        self.startedAt = startedAt
        placeText = ParkingEndProposalCopy.placeText(floor: floor, zone: zone, spot: spot)
    }

    init(_ snapshot: ActiveParkingSnapshot) {
        self.init(
            sessionId: snapshot.sessionId,
            startedAt: snapshot.startedAt,
            floor: snapshot.floorValue,
            zone: snapshot.zone,
            spot: snapshot.spot
        )
    }

    init(_ session: ParkingSession) {
        self.init(
            sessionId: session.id,
            startedAt: session.startedAt,
            floor: session.floor,
            zone: session.zone,
            spot: session.spot
        )
    }
}

// MARK: - Copy

/// The words of docs/02 §10 / docs/05 §11a. Never states the departure as fact.
enum ParkingEndProposalCopy {
    static let title = "출발한 것 같아요"
    static let endParking = "주차 종료"
    static let keepParking = "아직 주차 중"
    private static let question = "주차를 종료할까요?"

    /// `B3 · A구역 · 142 주차를 종료할까요?`, omitting whatever the record does not have.
    static func body(placeText: String?) -> String {
        guard let placeText else { return question }
        return "\(placeText) \(question)"
    }

    /// Floor, then the record's own zone/spot text, joined the way docs/10 §7b's history
    /// row joins them.
    static func placeText(floor: FloorValue?, zone: String?, spot: String?) -> String? {
        let parts = [floor?.displayText, ParkingSession.placeText(zone: zone, spot: spot)].compactMap(\.self)
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

// MARK: - Persistence

enum ParkingEndProposalStoreError: Error, Equatable {
    case writeFailed(String)
    case removeFailed(String)
}

/// The one pending proposal. Synchronous and `Sendable` like `ParkingCandidateStoring`: the
/// writer is the coordinator actor on a background wake, the reader the main actor later.
protocol ParkingEndProposalStoring: Sendable {
    /// Never throws: an unreadable file is a question the user is no longer asked, not an
    /// app failure — the record simply stays active.
    func load() -> ParkingEndProposal?
    func save(_ proposal: ParkingEndProposal) throws
    func clear() throws
}

/// JSON beside `candidate.json`, for `FileParkingCandidateStore`'s reasons: a plain file a
/// locked-device wake can write (`.completeUntilFirstUserAuthentication` on the directory),
/// replaced atomically.
struct FileParkingEndProposalStore: ParkingEndProposalStoring {
    /// Bumped whenever the encoded shape changes. An older payload is dropped: the record it
    /// asked about is still active, so nothing is lost but the question.
    static let schemaVersion = 1

    private static let fileName = "departure-proposal.json"

    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// Production location: the candidate's directory, which already carries the protection
    /// class a background wake needs.
    static func defaultFileURL() throws -> URL {
        try FileParkingCandidateStore.defaultFileURL()
            .deletingLastPathComponent()
            .appending(path: fileName, directoryHint: .notDirectory)
    }

    func load() -> ParkingEndProposal? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == Self.schemaVersion
        else {
            AppLog.detection.notice("departure proposal unreadable; treating as none")
            return nil
        }
        return envelope.proposal
    }

    func save(_ proposal: ParkingEndProposal) throws {
        do {
            let data = try JSONEncoder().encode(Envelope(schemaVersion: Self.schemaVersion, proposal: proposal))
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            throw ParkingEndProposalStoreError.writeFailed(Self.reason(for: error))
        }
    }

    func clear() throws {
        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch let error as CocoaError where error.code == .fileNoSuchFile {
            return
        } catch {
            throw ParkingEndProposalStoreError.removeFailed(Self.reason(for: error))
        }
    }

    /// Short and free of user data — it reaches diagnostics.
    private static func reason(for error: some Error) -> String {
        let nsError = error as NSError
        return "\(nsError.domain)(\(nsError.code))"
    }

    private struct Envelope: Codable {
        let schemaVersion: Int
        let proposal: ParkingEndProposal
    }
}

/// The store for a build with no directory to write to: proposes nothing, which leaves every
/// parking active until the user ends it — the safe direction.
struct UnavailableParkingEndProposalStore: ParkingEndProposalStoring {
    func load() -> ParkingEndProposal? {
        nil
    }

    func save(_: ParkingEndProposal) throws {}
    func clear() throws {}
}

// MARK: - Notification

/// The names the departure notification and the tap that comes back agree on. Its own
/// category beside `PARKING_CANDIDATE` (docs/04 §8's convention), never the candidate's:
/// the two questions have different answers.
enum ParkingEndProposalAction {
    static let categoryIdentifier = "PARKING_DEPARTURE"
    static let endParking = "END_PARKING"
    static let keepParking = "KEEP_PARKING"
    /// One request identifier for every proposal, so a newer one replaces the older on the
    /// lock screen and withdrawing never needs to know which one is showing.
    static let requestIdentifier = "\(categoryIdentifier).pending"
}

/// Posting and withdrawing the prompt. Nothing throws or reports success: notification
/// permission is not required for correctness (docs/05 §11a) — denied, the home row is the
/// only surface and nothing is lost.
protocol ParkingEndProposalNotifying: Sendable {
    func post(_ proposal: ParkingEndProposal, placeText: String?) async
    func withdraw() async
}

struct UserNotificationParkingEndProposalDelivery: ParkingEndProposalNotifying {
    private var center: UNUserNotificationCenter {
        .current()
    }

    func post(_: ParkingEndProposal, placeText: String?) async {
        PKNotificationCategories.register()

        let content = UNMutableNotificationContent()
        content.title = ParkingEndProposalCopy.title
        content.body = ParkingEndProposalCopy.body(placeText: placeText)
        content.categoryIdentifier = ParkingEndProposalAction.categoryIdentifier
        // Not time-sensitive: the user is driving, and the question keeps until they park.
        content.interruptionLevel = .active

        let request = UNNotificationRequest(
            identifier: ParkingEndProposalAction.requestIdentifier,
            content: content,
            trigger: nil
        )
        do {
            try await center.add(request)
        } catch {
            let nsError = error as NSError
            AppLog.detection.error(
                "departure notification failed: \(nsError.domain, privacy: .public)(\(nsError.code, privacy: .public))"
            )
        }
    }

    func withdraw() async {
        let identifier = ParkingEndProposalAction.requestIdentifier
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }
}

/// The lock screen's half of the proposal. Every outcome is a `ParkingModel` call, so the
/// notification and the home row cannot answer the same question differently.
@MainActor
final class ParkingEndProposalResponder: NotificationResponding {
    let categoryIdentifier = ParkingEndProposalAction.categoryIdentifier

    private let model: ParkingModel

    init(model: ParkingModel) {
        self.model = model
    }

    func handle(actionIdentifier: String, userInfo _: [String: String]) {
        // The process may have been launched for this tap, with nothing read yet.
        model.refresh()
        switch actionIdentifier {
        case ParkingEndProposalAction.endParking:
            _ = model.acceptEndProposal()
        case ParkingEndProposalAction.keepParking:
            model.keepParking()
        default:
            // A body tap opens the app on home, where the row asks again; a swipe-away is not
            // an answer and the proposal stays pending.
            break
        }
    }
}

// MARK: - Inbox

/// The main actor's handle on the pending proposal: read it, and retire it — clear the file
/// and withdraw the notification, the two halves that must never drift apart.
struct ParkingEndProposalInbox: Sendable {
    let store: any ParkingEndProposalStoring
    let notifier: any ParkingEndProposalNotifying

    func load() -> ParkingEndProposal? {
        store.load()
    }

    /// Idempotent. The returned task is the withdrawal, held so a test can await it.
    @discardableResult
    func retire() -> Task<Void, Never> {
        do {
            try store.clear()
        } catch {
            // The next refresh re-reads it and retires it again; the record is untouched.
            AppLog.detection.notice("departure proposal clear failed: \(String(describing: error), privacy: .public)")
        }
        let notifier = notifier
        return Task { await notifier.withdraw() }
    }
}

/// What the home card's prompt row shows (docs/05 §11a) — the notification's own words.
struct ParkingEndPrompt: Sendable, Equatable {
    let title: String
    let body: String
    let endTitle: String
    let keepTitle: String

    init(session: ParkingSession) {
        title = ParkingEndProposalCopy.title
        body = ParkingEndProposalCopy.body(placeText: ActiveParkingSummary(session).placeText)
        endTitle = ParkingEndProposalCopy.endParking
        keepTitle = ParkingEndProposalCopy.keepParking
    }
}
