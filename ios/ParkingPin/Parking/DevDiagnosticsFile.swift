#if PK_DEV
    import Foundation

    /// A small JSON file in the App Group's `Library/Application Support/Diagnostics/`.
    ///
    /// That directory, and not the container root, because it is the only part of a shared
    /// container `devicectl device copy from` will read:
    ///
    /// ```sh
    /// xcrun devicectl device copy from --device <udid> \
    ///   --domain-type appGroupDataContainer --domain-identifier group.com.sjstudioz.parkingpin \
    ///   --source "Library/Application Support/Diagnostics/<name>" --destination ./<name>
    /// ```
    ///
    /// Best-effort by design: a diagnostics file that fails to write must never cost the
    /// feature it describes anything. Compiled out of STAGING and PROD entirely.
    enum DevDiagnosticsFile {
        static func read(_ name: String) -> [String: Any] {
            guard let url = url(for: name),
                  let data = try? Data(contentsOf: url),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return [:] }
            return object
        }

        static func write(_ payload: [String: Any], to name: String) {
            guard let url = url(for: name) else { return }
            try? FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            guard let data = try? JSONSerialization.data(
                withJSONObject: payload,
                options: [.prettyPrinted, .sortedKeys]
            ) else { return }
            try? data.write(to: url, options: .atomic)
        }

        private static func url(for name: String) -> URL? {
            guard let identifier = Bundle.main
                .object(forInfoDictionaryKey: "PKAppGroupIdentifier") as? String,
                let container = FileManager.default
                .containerURL(forSecurityApplicationGroupIdentifier: identifier)
            else { return nil }
            return container.appending(
                path: "Library/Application Support/Diagnostics/\(name)",
                directoryHint: .notDirectory
            )
        }
    }
#endif
