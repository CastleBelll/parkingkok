import AVFAudio
import Foundation

/// What the platform currently says about the car link (docs/05 §3a "The car link").
///
/// A **sample**, not an event: on iOS this is all that is reachable, so the adapter
/// compares consecutive samples and derives the connect/disconnect edges itself.
struct CarLinkObservation: Sendable, Equatable {
    var connected: Set<CarLinkKind> = []
    /// Why the sample could not be taken, or `nil`. "No car" and "no answer" must not look
    /// the same in the field report.
    var failure: String?

    static let none = CarLinkObservation()
}

protocol CarLinkObserving: Sendable {
    func observe() -> CarLinkObservation
}

/// Reads the car link from the audio route.
///
/// ### What iOS actually exposes — checked against the iOS 27.0 SDK, 2026-09-19
///
/// `docs/05_PARKING_DETECTION_ENGINE.md` §3a asked for this to be verified rather than
/// taken on trust. It is accurate, with one addition worth having:
///
/// * **Classic Bluetooth connect/disconnect is not available.** `CoreBluetooth` is
///   BLE-only — `CBCentralManager` has no classic transport at all, and its one mention of
///   the words ("the system will bring up classic transport profiles when low energy
///   transport for peripheral is connected") is a connection *option* for an LE peripheral,
///   not an observation channel. `ExternalAccessory` needs an MFi accessory and a protocol
///   string in `Info.plist`; a car's hands-free profile is not one.
///   `AccessorySetupKit` (iOS 18+, present in this SDK) matches on `CBUUID` service data
///   and manufacturer data — it is a BLE/Wi-Fi pairing flow for an accessory the app
///   declares in advance, not a way to notice an arbitrary head unit.
/// * **The audio route is reachable, and it covers both link kinds.**
///   `AVAudioSession.currentRoute` is a `readonly` property with no activation requirement,
///   and `AVAudioSession.Port.carAudio` is the port a car head unit presents over both
///   CarPlay and Bluetooth. So one read answers both rows of §3a's table.
/// * **What is *not* reachable is the route-change event in the background.**
///   `AVAudioSession.routeChangeNotification` is delivered to a running process; receiving
///   it while suspended needs an active audio session and the `audio` background mode,
///   which means holding an audio session this app has no other reason to hold. That is
///   the cost §3a describes and this build does not pay it.
/// * **One thing §3a did not know about.** `com.apple.developer.carplay-parking` exists in
///   this SDK's entitlement list — a CarPlay category matched to exactly this product. It
///   would give `CPTemplateApplicationSceneDelegate`'s real connect/disconnect callbacks,
///   including in the background. It needs Apple's approval and a CarPlay UI, so it is not
///   an M0 option, but it is the path that turns the projection row from a poll into an
///   event. Raised to the contract rather than implemented here.
///
/// ### The consequence for the engine
/// The link therefore arrives **sampled at each wake**, minutes late in the worst case,
/// and never at all when the app is not woken. That is exactly why §3a calls it optional
/// and why no fixture may depend on it: every transition still stands on motion and
/// location alone.
struct AudioRouteCarLinkObserver: CarLinkObserving {
    /// Ports a car presents. `.carAudio` is what both CarPlay and a car's Bluetooth
    /// hands-free/A2DP profile report, so the two §3a rows share one probe.
    static let carPorts: Set<AVAudioSession.Port> = [.carAudio]

    func observe() -> CarLinkObservation {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let isCar = outputs.contains { Self.carPorts.contains($0.portType) }
        // The route cannot tell CarPlay from the car's Bluetooth stereo — both are
        // `carAudio`. §3a only weighs the *kind* in §8, and reporting a projection we
        // cannot prove would overstate the evidence, so the honest answer is the weaker one.
        return CarLinkObservation(connected: isCar ? [.bluetoothAudio] : [], failure: nil)
    }
}
