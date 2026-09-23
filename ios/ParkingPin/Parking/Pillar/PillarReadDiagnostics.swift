#if PK_DEV
    import Foundation

    /// What the recogniser saw, and what the rule made of it.
    ///
    /// A pillar read that comes back empty is indistinguishable, from outside the app, from
    /// a rule that rejected everything — and on a real B2 garage those two were confused for
    /// an afternoon. The lines are the only evidence that settles it, and there is no way to
    /// get them off a phone otherwise: the reader runs on device, the recognised text never
    /// reaches a log (docs/09 §11 keeps photo-derived text out of one), and iOS cannot be
    /// driven from the command line.
    ///
    /// So the last read is written into the App Group container, where
    /// `devicectl device copy from` can read it:
    ///
    /// ```sh
    /// xcrun devicectl device copy from --device <udid> \
    ///   --domain-type appGroupDataContainer --domain-identifier group.com.sjstudioz.parkingpin \
    ///   --source "Library/Application Support/Diagnostics/pillar.json" --destination ./pillar.json
    /// ```
    ///
    /// Text painted on a public wall, never a coordinate and never the photo. Compiled out
    /// of STAGING and PROD entirely.
    enum PillarReadDiagnostics {
        static func record(lines: [String], chose floorText: String?) {
            guard let identifier = Bundle.main
                .object(forInfoDictionaryKey: "PKAppGroupIdentifier") as? String,
                let container = FileManager.default
                .containerURL(forSecurityApplicationGroupIdentifier: identifier)
            else { return }

            let payload: [String: Any] = [
                "lines": lines,
                "chose": floorText ?? "none",
                "readAt": ISO8601DateFormatter().string(from: Date()),
            ]
            let directory = container.appending(
                path: "Library/Application Support/Diagnostics",
                directoryHint: .isDirectory
            )
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            guard let data = try? JSONSerialization.data(withJSONObject: payload, options: .prettyPrinted)
            else { return }
            try? data.write(
                to: directory.appending(path: "pillar.json", directoryHint: .notDirectory),
                options: .atomic
            )
        }
    }
#endif
