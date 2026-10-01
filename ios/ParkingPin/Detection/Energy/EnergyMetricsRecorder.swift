#if PK_DEV
    import Foundation
    import MetricKit

    /// docs/05 §19 battery gate, iOS half: MetricKit's daily payloads, written to the DEV
    /// diagnostics directory as `energy-metrics.json` (see `DevDiagnosticsFile` for the
    /// `devicectl` command that reads it).
    ///
    /// MetricKit rather than battery-level sampling or Instruments: it attributes CPU and
    /// location time to this app alone, it accumulates on ordinary drives with no cable, and
    /// it holds no coordinate. Compiled out of STAGING and PROD.
    final class EnergyMetricsRecorder: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
        static let shared = EnergyMetricsRecorder()
        static let fileName = "energy-metrics.json"

        /// Serializes the read-merge-write of the log; MetricKit calls back on its own queue.
        private let lock = NSLock()

        func start() {
            MXMetricManager.shared.add(self)
            record(MXMetricManager.shared.pastPayloads)
        }

        func didReceive(_ payloads: [MXMetricPayload]) {
            record(payloads)
        }

        private func record(_ payloads: [MXMetricPayload]) {
            guard !payloads.isEmpty else { return }
            lock.lock()
            defer { lock.unlock() }
            let existing = DevDiagnosticsFile.readDecodable([EnergyDay].self, from: Self.fileName) ?? []
            let merged = EnergyMetricsLog.merging(payloads.map(Self.energyDay), into: existing)
            DevDiagnosticsFile.writeEncodable(merged, to: Self.fileName)
        }

        private static func energyDay(from payload: MXMetricPayload) -> EnergyDay {
            let time = payload.applicationTimeMetrics
            let location = payload.locationActivityMetrics
            return EnergyDay(
                periodStart: payload.timeStampBegin,
                periodEnd: payload.timeStampEnd,
                appBuild: payload.metaData?.applicationBuildVersion,
                cpuSeconds: seconds(payload.cpuMetrics?.cumulativeCPUTime),
                foregroundSeconds: seconds(time?.cumulativeForegroundTime),
                backgroundSeconds: seconds(time?.cumulativeBackgroundTime),
                backgroundLocationSeconds: seconds(time?.cumulativeBackgroundLocationTime),
                locationBestForNavigationSeconds: seconds(location?.cumulativeBestAccuracyForNavigationTime),
                locationBestSeconds: seconds(location?.cumulativeBestAccuracyTime),
                locationTenMetersSeconds: seconds(location?.cumulativeNearestTenMetersAccuracyTime),
                locationHundredMetersSeconds: seconds(location?.cumulativeHundredMetersAccuracyTime),
                locationKilometerSeconds: seconds(location?.cumulativeKilometerAccuracyTime),
                locationThreeKilometersSeconds: seconds(location?.cumulativeThreeKilometersAccuracyTime)
            )
        }

        private static func seconds(_ measurement: Measurement<UnitDuration>?) -> Double? {
            measurement?.converted(to: .seconds).value
        }
    }
#endif
