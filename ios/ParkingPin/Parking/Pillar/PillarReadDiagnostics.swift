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
        static func record(lines: [String], chose floorText: String?, zone: String?, spot: String?) {
            DevDiagnosticsFile.write([
                "lines": lines,
                "chose": floorText ?? "none",
                "zone": zone ?? "none",
                "spot": spot ?? "none",
                "readAt": ISO8601DateFormatter().string(from: Date())
            ], to: "pillar.json")
        }
    }
#endif
