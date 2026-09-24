#if PK_DEV
    import Foundation

    /// What the last manual save got from each location source, and why not when it got
    /// nothing.
    ///
    /// Two records saved by hand on 2026-09-24 kept no location on iOS while the Android
    /// phone on the same trip kept one for each, and nothing on the device could say which
    /// source had failed: a `nil` from the checkpoint and a `nil` from the one-shot fix
    /// look the same once the record is written. This file is the evidence the next save
    /// leaves behind, read with `devicectl` as `save-location.json` (see [DevDiagnosticsFile]).
    ///
    /// Outcomes and ages only — accuracy in metres and seconds since capture. Never a
    /// coordinate (docs/09 §11).
    enum SaveLocationDiagnostics {
        private static let fileName = "save-location.json"

        /// A new save: everything the previous one left behind is replaced.
        static func begin(at date: Date) {
            DevDiagnosticsFile.write(["savedAt": timestamp(date)], to: fileName)
        }

        /// One stage's outcome, added to the current save's file.
        static func note(_ stage: String, _ outcome: String) {
            var payload = DevDiagnosticsFile.read(fileName)
            payload[stage] = outcome
            payload["\(stage)At"] = timestamp(Date())
            DevDiagnosticsFile.write(payload, to: fileName)
        }

        private static func timestamp(_ date: Date) -> String {
            ISO8601DateFormatter().string(from: date)
        }
    }
#endif
