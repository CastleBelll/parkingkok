import Foundation
import Testing
@testable import ParkingPin

/// docs/05 §19: the log the iOS battery gate is read from.
@Suite("Energy metrics log")
struct EnergyMetricsLogTests {
    private let day: TimeInterval = 24 * 60 * 60

    private func energyDay(_ index: Int, cpu: Double = 1, fraction: TimeInterval = 0) -> EnergyDay {
        let start = Date(timeIntervalSince1970: 1_790_000_000 + Double(index) * day + fraction)
        return EnergyDay(
            periodStart: start,
            periodEnd: start.addingTimeInterval(day),
            appBuild: "1",
            cpuSeconds: cpu,
            foregroundSeconds: nil,
            backgroundSeconds: nil,
            backgroundLocationSeconds: nil,
            locationBestForNavigationSeconds: nil,
            locationBestSeconds: nil,
            locationTenMetersSeconds: nil,
            locationHundredMetersSeconds: nil,
            locationKilometerSeconds: nil,
            locationThreeKilometersSeconds: nil
        )
    }

    @Test("A period delivered again replaces the one held, it is not repeated")
    func redeliveredPeriodReplaces() {
        // Arrange — MetricKit hands past payloads back on every launch.
        let held = [energyDay(0, cpu: 1), energyDay(1, cpu: 2)]

        // Act
        let merged = EnergyMetricsLog.merging([energyDay(1, cpu: 3)], into: held)

        // Assert
        #expect(merged == [energyDay(0, cpu: 1), energyDay(1, cpu: 3)])
    }

    @Test("A fresh payload's fractional second matches the period the file stored")
    func fractionalSecondsMatch() {
        // Arrange — the file keeps ISO 8601, which drops the fraction.
        let held = [energyDay(0, cpu: 1)]

        // Act
        let merged = EnergyMetricsLog.merging([energyDay(0, cpu: 2, fraction: 0.25)], into: held)

        // Assert
        #expect(merged.count == 1)
        #expect(merged.first?.cpuSeconds == 2)
    }

    @Test("Days are kept oldest first, and only the newest maximumDays")
    func orderedAndCapped() {
        // Arrange
        let incoming = (0..<(EnergyMetricsLog.maximumDays + 5)).reversed().map { energyDay($0) }

        // Act
        let merged = EnergyMetricsLog.merging(incoming, into: [])

        // Assert
        #expect(merged.count == EnergyMetricsLog.maximumDays)
        #expect(merged.first == energyDay(5))
        #expect(merged.last == energyDay(EnergyMetricsLog.maximumDays + 4))
    }

    @Test("Nothing in, nothing changes")
    func emptyIncoming() {
        let held = [energyDay(0)]
        #expect(EnergyMetricsLog.merging([], into: held) == held)
        #expect(EnergyMetricsLog.merging([], into: []).isEmpty)
    }

    @Test("The log round-trips through the file's ISO 8601 encoding")
    func roundTrips() throws {
        // Arrange
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let days = [energyDay(0), energyDay(1)]

        // Act
        let decoded = try decoder.decode([EnergyDay].self, from: encoder.encode(days))

        // Assert
        #expect(decoded == days)
    }
}
