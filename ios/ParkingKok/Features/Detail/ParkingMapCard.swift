import MapKit
import SwiftUI

/// The map panel at the top of `design-references/03-parking-detail.png` (FR-008).
///
/// SwiftUI `Map`, per docs/04 §9 — no server-rendered snapshot, so the coordinate never
/// becomes an HTTP request to anyone but Apple's own tile service.
///
/// Two departures from the mock, both deliberate:
/// - The mock's pin sits on an exact spot with a `B3` chip beside it. The accuracy
///   circle is drawn underneath it here. Underground, `horizontalAccuracy` is commonly
///   40m+, and a bare pin would be the "차량 정확한 위치" claim FR-008 forbids.
/// - The map carries a caption chip reading `마지막으로 확인된 위치 · 약 18m 이내`.
///   docs/19 §3 explicitly allows the map to be accompanied by copy explaining that it
///   is the last *reliable* location, and FR-008 fixes that wording.
struct ParkingMapCard: View {
    /// Tall enough to read a street off, short enough that the hero floor below it stays
    /// the first thing on the screen (docs/10 §6 ranks the floor first).
    static let height: CGFloat = 180

    private let point: ParkingMapPoint
    private let floorText: String?

    init(point: ParkingMapPoint, floorText: String?) {
        self.point = point
        self.floorText = floorText
    }

    var body: some View {
        map
            .frame(height: Self.height)
            .clipShape(.rect(cornerRadius: PKRadius.card))
            .overlay(alignment: .bottomLeading) { caption }
            .overlay {
                RoundedRectangle(cornerRadius: PKRadius.card)
                    .strokeBorder(PKColor.divider, lineWidth: PKSize.hairline)
            }
            // Inert on purpose. A live map inside a `ScrollView` steals the drag, and
            // panning it has no product meaning — 길찾기 is what opens a real map.
            .allowsHitTesting(false)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
    }

    private var map: some View {
        Map(initialPosition: .region(region), interactionModes: []) {
            MapCircle(center: point.coordinate, radius: point.accuracyMeters)
                .foregroundStyle(PKColor.primary.opacity(0.15))
                .stroke(PKColor.primary.opacity(0.45), lineWidth: PKSize.hairline)
            // The title would repeat the caption chip underneath it and, at this zoom,
            // cover the circle it is meant to sit in — so it is carried for VoiceOver
            // and hidden on the map.
            Annotation(ParkingMapPoint.label, coordinate: point.coordinate) {
                pin
            }
            .annotationTitles(.hidden)
        }
    }

    /// The mock's car pin, with its floor chip when there is a floor to show.
    private var pin: some View {
        HStack(spacing: PKSpacing.xs) {
            Image(systemName: "car.fill")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Color.white)
                .frame(width: 30, height: 30)
                .background(PKColor.primary, in: .circle)
                .overlay { Circle().strokeBorder(Color.white, lineWidth: 2) }
            if let floorText {
                Text(floorText)
                    .font(PKTypography.caption)
                    .foregroundStyle(PKColor.primary)
                    .padding(.horizontal, PKSpacing.s)
                    .padding(.vertical, PKSpacing.xs)
                    .background(PKColor.surface, in: .rect(cornerRadius: PKRadius.chip))
            }
        }
    }

    private var caption: some View {
        Text(point.captionText)
            .font(PKTypography.caption)
            .foregroundStyle(PKColor.textPrimary)
            .padding(.horizontal, PKSpacing.s)
            .padding(.vertical, PKSpacing.xs)
            .background(PKColor.surface.opacity(0.9), in: .rect(cornerRadius: PKRadius.chip))
            .padding(PKSpacing.m)
    }

    private var accessibilityText: String {
        let floor = floorText.map { "\($0), " } ?? ""
        return "\(floor)\(point.captionText)"
    }

    private var region: MKCoordinateRegion {
        MKCoordinateRegion(
            center: point.coordinate,
            latitudinalMeters: point.spanMeters,
            longitudinalMeters: point.spanMeters
        )
    }
}

/// What stands in for the map when the record has no coordinate (FR-001: a parking saved
/// without location permission is a normal parking).
///
/// Not a greyed-out map and not a blank space: the mock's slot keeps its size and says
/// why it is empty, so the screen does not silently lose a section.
struct ParkingMapUnavailableCard: View {
    var body: some View {
        PKCard {
            HStack(spacing: PKSpacing.l) {
                PKIconChip("mappin.slash", tint: .neutral)
                VStack(alignment: .leading, spacing: 2) {
                    Text("위치 정보 없이 저장된 기록이에요")
                        .font(PKTypography.row)
                        .foregroundStyle(PKColor.textPrimary)
                    Text("층과 구역 정보는 그대로 사용할 수 있어요.")
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(PKSpacing.l)
        }
        .accessibilityElement(children: .combine)
    }
}
