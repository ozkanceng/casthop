import Foundation

/// SemVer (major.minor.patch + optional pre-release suffix) used for cross-platform update checks.
public struct SemVer: Equatable, Sendable {
    public let major: Int
    public let minor: Int
    public let patch: Int
    public let prerelease: Bool

    public init(major: Int, minor: Int, patch: Int, prerelease: Bool = false) {
        self.major = major
        self.minor = minor
        self.patch = patch
        self.prerelease = prerelease
    }

    public static func parse(_ raw: String) -> SemVer? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") { s.removeFirst() }
        if let plus = s.firstIndex(of: "+") { s = String(s[..<plus]) }
        var prerelease = false
        if let dash = s.firstIndex(of: "-") {
            let suffix = s[s.index(after: dash)...]
            guard !suffix.isEmpty else { return nil }
            prerelease = true
            s = String(s[..<dash])
        }
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let maj = Int(parts[0]), maj >= 0,
              let min = Int(parts[1]), min >= 0,
              let pat = Int(parts[2]), pat >= 0 else {
            return nil
        }
        return SemVer(major: maj, minor: min, patch: pat, prerelease: prerelease)
    }

    /// Returns true if `self` is newer than `current`. Stable builds are never offered pre-releases.
    public func isNewer(than current: SemVer) -> Bool {
        if prerelease && !current.prerelease { return false }
        if major != current.major { return major > current.major }
        if minor != current.minor { return minor > current.minor }
        if patch != current.patch { return patch > current.patch }
        return current.prerelease && !prerelease
    }
}

/// Resolved update metadata for a single platform channel or App Store listing.
public struct UpdateRelease: Equatable, Sendable {
    public let currentVersion: String
    public let latestVersion: String
    public let notes: String
    public let url: URL
    public let isAppStore: Bool

    public init(currentVersion: String, latestVersion: String, notes: String, url: URL, isAppStore: Bool) {
        self.currentVersion = currentVersion
        self.latestVersion = latestVersion
        self.notes = notes
        self.url = url
        self.isAppStore = isAppStore
    }
}

/// Shared update manifest parser and anonymous HTTPS client (no device or user identifiers sent).
public enum UpdateClient {
    public static let defaultManifestURL = URL(string: "https://ozkanceng.github.io/casthop/version.json")!
    public static let fallbackReleaseURL = URL(string: "https://github.com/ozkanceng/casthop/releases/latest")!
    public static let maxPayloadBytes = 64 * 1024
    public static let timeoutSeconds: TimeInterval = 4.0
    public static let checkIntervalSeconds: TimeInterval = 24 * 60 * 60

    public static func isSafeHttpsURL(_ raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(), scheme == "https",
              let host = url.host, !host.isEmpty else {
            return nil
        }
        return url
    }

    /// Parses `site/version.json` for a specific channel (e.g. `"macos"`).
    public static func parseManifest(data: Data, channel: String, currentVersion: String, preferTurkish: Bool) -> UpdateRelease? {
        guard !data.isEmpty, data.count <= maxPayloadBytes,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        let channels = root["channels"] as? [String: Any]
        let ch = channels?[channel] as? [String: Any]

        let rawVersion = ((ch?["version"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
            ?? (root["latest"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let version = rawVersion, SemVer.parse(version) != nil else { return nil }

        let rawURL = ((ch?["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
            ?? (root["releaseUrl"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            ?? ""
        let url = isSafeHttpsURL(rawURL) ?? fallbackReleaseURL

        let notesDict = root["notes"] as? [String: Any]
        let tr = (notesDict?["tr"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let en = (notesDict?["en"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let notes = preferTurkish ? (tr.isEmpty ? en : tr) : (en.isEmpty ? tr : en)

        return UpdateRelease(currentVersion: currentVersion, latestVersion: version, notes: notes, url: url, isAppStore: false)
    }

    /// Parses Apple's public iTunes Lookup JSON (`itunes.apple.com/lookup?bundleId=...`) for approved App Store versions.
    public static func parseITunesLookup(data: Data, currentVersion: String) -> UpdateRelease? {
        guard !data.isEmpty, data.count <= maxPayloadBytes,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let results = root["results"] as? [[String: Any]],
              let first = results.first,
              let version = (first["version"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              SemVer.parse(version) != nil else {
            return nil
        }
        let rawURL = (first["trackViewUrl"] as? String) ?? ""
        guard let url = isSafeHttpsURL(rawURL),
              let host = url.host?.lowercased(),
              host == "apps.apple.com" || host.hasSuffix(".apple.com") else {
            return nil
        }
        let notes = (first["releaseNotes"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return UpdateRelease(currentVersion: currentVersion, latestVersion: version, notes: notes, url: url, isAppStore: true)
    }

    /// Fetches `version.json` for desktop/receiver builds. Returns `nil` silently on network/parse error.
    public static func fetchManifestRelease(channel: String, currentVersion: String, preferTurkish: Bool,
                                            manifestURL: URL = defaultManifestURL) async -> UpdateRelease? {
        guard let data = await fetchBytes(from: manifestURL) else { return nil }
        return parseManifest(data: data, channel: channel, currentVersion: currentVersion, preferTurkish: preferTurkish)
    }

    /// Fetches the live App Store version for an iOS bundle ID via iTunes Lookup.
    public static func fetchAppStoreRelease(bundleId: String, currentVersion: String, countryCode: String) async -> UpdateRelease? {
        let country = countryCode.count == 2 ? countryCode.lowercased() : "tr"
        var comps = URLComponents(string: "https://itunes.apple.com/lookup")
        comps?.queryItems = [
            URLQueryItem(name: "bundleId", value: bundleId),
            URLQueryItem(name: "country", value: country),
        ]
        guard let url = comps?.url, let data = await fetchBytes(from: url) else { return nil }
        if let hit = parseITunesLookup(data: data, currentVersion: currentVersion) { return hit }
        // Fallback to global store if regional storefront returned empty results.
        if country != "us" {
            comps?.queryItems = [URLQueryItem(name: "bundleId", value: bundleId)]
            if let fallbackURL = comps?.url, let fallbackData = await fetchBytes(from: fallbackURL) {
                return parseITunesLookup(data: fallbackData, currentVersion: currentVersion)
            }
        }
        return nil
    }

    private static func fetchBytes(from url: URL) async -> Data? {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeoutSeconds
        config.timeoutIntervalForResource = timeoutSeconds
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        var req = URLRequest(url: url)
        req.setValue("CastHop-UpdateCheck", forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  data.count <= maxPayloadBytes else {
                return nil
            }
            return data
        } catch {
            return nil
        }
    }
}
