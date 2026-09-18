import MapKit
import SwiftUI
import UIKit

/// The map panel in the top-right corner of the hero card (`01-home-main.png`).
///
/// The mock fills that corner; without it roughly 40% of the card is empty and the hero
/// floor floats in white space with nothing to sit against. It is also the only colour on
/// the card that is not a tint of the brand blue.
///
/// **A snapshot, not a live `Map`.** The detail screen's `ParkingMapCard` is a real map
/// view because it is big enough to be one. At 112pt a live map is all chrome: MapKit puts
/// its mandatory `법적 정보` link inside the view, which at this size lands across the
/// bottom third. `MKMapSnapshotter` renders the same tiles with Apple's attribution baked
/// into the image, which is what the corner of a card can actually carry. It goes to
/// Apple's tile service and nobody else (docs/04 §9), exactly as the live map does.
///
/// FR-008 applies here too: the accuracy circle is drawn under the pin, and the VoiceOver
/// label is `마지막으로 확인된 위치`, never a claim about the exact car position.
struct ParkingMapThumbnail: View {
    /// Square, and about 40% of the card — the proportion the mock uses. Scales with
    /// Dynamic Type so it does not shrink into a stamp beside a grown hero.
    @ScaledMetric(relativeTo: .body) private var side: CGFloat = 112

    @Environment(\.colorScheme) private var colorScheme
    @State private var snapshot: UIImage?

    private let point: ParkingMapPoint?
    private let zoneText: String?

    init(point: ParkingMapPoint?, zoneText: String?) {
        self.point = point
        self.zoneText = zoneText
    }

    var body: some View {
        content
            .frame(width: side, height: side)
            .clipShape(.rect(cornerRadius: PKRadius.row))
            .overlay {
                RoundedRectangle(cornerRadius: PKRadius.row)
                    .strokeBorder(PKColor.divider, lineWidth: PKSize.hairline)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
            // Rendering tiles is work, so it belongs in a task, never in `body`
            // (docs/16 §5). Re-runs only when what it would draw actually changed —
            // a new coordinate, a new text size, or a switch between light and dark.
            .task(id: request) { await loadSnapshot() }
    }

    @ViewBuilder
    private var content: some View {
        if point != nil {
            ZStack {
                // The tinted panel is also what shows while the tiles render, so the card
                // never flashes from empty to full.
                PKColor.primarySoft
                if let snapshot {
                    Image(uiImage: snapshot)
                        .resizable()
                        .scaledToFill()
                }
                accuracyCircle
                pin
            }
            .overlay(alignment: .bottomTrailing) { zoneChip }
        } else {
            placeholder
        }
    }

    /// The honest half of the pin. The region is centred on the coordinate and spans
    /// `spanMeters` edge to edge, so the circle's radius in points is that ratio — no
    /// projection maths and no dependence on where the snapshot put anything.
    @ViewBuilder
    private var accuracyCircle: some View {
        if let point {
            let diameter = side * CGFloat(point.accuracyMeters / point.spanMeters) * 2
            Circle()
                .fill(PKColor.primary.opacity(0.15))
                .overlay { Circle().strokeBorder(PKColor.primary.opacity(0.45), lineWidth: PKSize.hairline) }
                .frame(width: diameter, height: diameter)
        }
    }

    /// Deliberately smaller than the accuracy circle it sits in, so the circle is the
    /// thing you read and the pin is what it contains — the same honesty the detail
    /// screen's map card is built around.
    private var pin: some View {
        Image(systemName: "car.fill")
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(Color.white)
            .frame(width: 22, height: 22)
            .background(PKColor.primary, in: .circle)
            .overlay { Circle().strokeBorder(Color.white, lineWidth: 2) }
    }

    /// `A구역`, as the mock prints it over the bottom-right of the thumbnail.
    @ViewBuilder
    private var zoneChip: some View {
        if let zoneText {
            Text(zoneText)
                .font(PKTypography.caption)
                .foregroundStyle(PKColor.accent)
                .lineLimit(1)
                .padding(.horizontal, PKSpacing.s)
                .padding(.vertical, 3)
                .background(PKColor.surface.opacity(0.92), in: .rect(cornerRadius: PKRadius.chip))
                .padding(PKSpacing.xs)
        }
    }

    /// FR-001: a parking saved with location permission denied is a normal parking. The
    /// corner still gets filled — an empty hole would be the dead space this component
    /// exists to remove — and it says why there is no map instead of showing a grey box.
    private var placeholder: some View {
        ZStack {
            PKColor.primarySoft
            VStack(spacing: PKSpacing.xs) {
                Image(systemName: "mappin.slash")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(PKColor.primary)
                Text("위치 없음")
                    .font(PKTypography.caption)
                    .foregroundStyle(PKColor.textSecondary)
            }
        }
    }

    private var accessibilityText: String {
        point?.captionText ?? "위치 정보 없이 저장된 기록이에요"
    }

    /// Everything the rendered image depends on, so `.task(id:)` can tell a real change
    /// from a re-render.
    private var request: ParkingMapSnapshotRequest? {
        point.map { ParkingMapSnapshotRequest(point: $0, side: side, isDark: colorScheme == .dark) }
    }

    private func loadSnapshot() async {
        guard let request else {
            snapshot = nil
            return
        }
        snapshot = await ParkingMapSnapshotRenderer.render(request)
    }
}

/// What a thumbnail needs drawn. A value so it can be compared.
struct ParkingMapSnapshotRequest: Equatable, Sendable {
    let point: ParkingMapPoint
    let side: CGFloat
    let isDark: Bool
}

/// The MapKit call, kept out of the view.
///
/// The completion-handler form rather than the `async` one: `MKMapSnapshotter.Snapshot` is
/// not `Sendable`, so it must not cross back out of the callback. Only the `UIImage` it
/// carries does.
enum ParkingMapSnapshotRenderer {
    static func render(_ request: ParkingMapSnapshotRequest) async -> UIImage? {
        let options = MKMapSnapshotter.Options()
        options.region = MKCoordinateRegion(
            center: request.point.coordinate,
            latitudinalMeters: request.point.spanMeters,
            longitudinalMeters: request.point.spanMeters
        )
        options.size = CGSize(width: request.side, height: request.side)
        options.traitCollection = UITraitCollection(userInterfaceStyle: request.isDark ? .dark : .light)
        // A thumbnail this size cannot show a label legibly, and the pin is the only thing
        // on it that matters.
        options.pointOfInterestFilter = .excludingAll

        let snapshotter = MKMapSnapshotter(options: options)
        return await withCheckedContinuation { continuation in
            snapshotter.start(with: .global(qos: .userInitiated)) { snapshot, _ in
                // A failed render is not a failure of the screen: the tinted panel stays,
                // the pin stays, and the parking is still readable (docs/01 §8 offline).
                continuation.resume(returning: snapshot?.image)
            }
        }
    }
}
