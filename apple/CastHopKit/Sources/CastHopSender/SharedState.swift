import CastHopProtocol
import Foundation

/// Pure logic for distinguishing boards with identical default hostnames (e.g. Pardus ETAP "etap"),
/// sorting favorites/recent first, validating IPv4 addresses, and sanitizing 6-digit OTP input.
///
/// The 4-hex short ID (`A4F2`) uses the first 2 bytes of the SHA-256 certificate fingerprint so mDNS TXT `fp=`
/// prefixes, remembered boards, and receiver waiting screens all show the exact same identifier.
public enum BoardIdentity {
    public static func shortId(_ fingerprintOrPrefix: Data) -> String {
        guard fingerprintOrPrefix.count >= 2 else {
            let hex = fingerprintOrPrefix.map { String(format: "%02X", $0) }.joined()
            return String(repeating: "0", count: max(0, 4 - hex.count)) + String(hex.prefix(4))
        }
        return String(format: "%02X%02X", fingerprintOrPrefix[fingerprintOrPrefix.startIndex],
                      fingerprintOrPrefix[fingerprintOrPrefix.index(after: fingerprintOrPrefix.startIndex)])
    }

    public static func isValidIpv4(_ text: String) -> Bool {
        QrPayload.isIPv4(text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    public static func primaryIpv4(_ hosts: [String]) -> String? {
        hosts.first { isValidIpv4($0) }
    }

    public static func ipLastOctet(_ hosts: [String]) -> String? {
        guard let ip = primaryIpv4(hosts), let dot = ip.lastIndex(of: ".") else { return nil }
        let suffix = String(ip[dot...])
        return suffix.count > 1 ? suffix : nil
    }

    public static func matchesPrefix(fingerprint: Data, prefix: Data) -> Bool {
        !prefix.isEmpty && fingerprint.starts(with: prefix)
    }

    /// Card subtitle: raw name (when nicknamed) + last IPv4 octet + `#XXXX`.
    public static func subtitle(hosts: [String], fingerprintOrPrefix: Data, rawName: String, nickname: String? = nil) -> String {
        var parts: [String] = []
        let cleanNick = nickname?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanRaw = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanNick.isEmpty && cleanNick.caseInsensitiveCompare(cleanRaw) != .orderedSame {
            parts.append(cleanRaw)
        }
        if let oct = ipLastOctet(hosts) {
            parts.append("IP \(oct)")
        }
        parts.append("#\(shortId(fingerprintOrPrefix))")
        return parts.joined(separator: " · ")
    }

    public static func sortReceivers(_ receivers: [RememberedReceiver]) -> [RememberedReceiver] {
        receivers.sorted { lhs, rhs in
            if lhs.favorite != rhs.favorite { return lhs.favorite }
            if lhs.lastUsedMs != rhs.lastUsedMs { return lhs.lastUsedMs > rhs.lastUsedMs }
            return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
        }
    }

    public static func sortDiscovered(_ discovered: [DiscoveredReceiver], remembered: [RememberedReceiver]) -> [DiscoveredReceiver] {
        func match(_ d: DiscoveredReceiver) -> RememberedReceiver? {
            remembered.first { matchesPrefix(fingerprint: $0.fingerprint, prefix: d.fingerprintPrefix) }
        }
        return discovered.sorted { lhs, rhs in
            let lm = match(lhs)
            let rm = match(rhs)
            let lFav = lm?.favorite ?? false
            let rFav = rm?.favorite ?? false
            if lFav != rFav { return lFav }
            let lLast = lm?.lastUsedMs ?? Int64.min
            let rLast = rm?.lastUsedMs ?? Int64.min
            if lLast != rLast { return lLast > rLast }
            let lName = (lm?.displayName ?? lhs.name)
            let rName = (rm?.displayName ?? rhs.name)
            let cmp = lName.localizedCaseInsensitiveCompare(rName)
            if cmp != .orderedSame { return cmp == .orderedAscending }
            return shortId(lhs.fingerprintPrefix) < shortId(rhs.fingerprintPrefix)
        }
    }

    public static func sanitizeOtp(_ raw: String, length: Int = 6) -> String {
        String(raw.filter(\.isNumber).prefix(length))
    }
}

/// A receiver paired once. Later connections need no QR (PROTOCOL.md §7.1, auth_method=3).
public struct RememberedReceiver: Codable, Equatable, Identifiable, Sendable {
    public var name: String
    /// Empty if the receiver was found by Bonjour over IPv6 only; it is then looked up again.
    public var hosts: [String]
    public var port: UInt16
    /// Pinned on every connection.
    public var fingerprint: Data
    public var keyId: Data
    public var pairKey: Data
    public var lastUsedMs: Int64
    /// Optional user-assigned nickname (e.g. "9-B Matematik").
    public var nickname: String?
    /// Pinned favorite boards stay at the top of both home and discovery lists.
    public var favorite: Bool
    /// Receiver kind ("board", "tv", "pc", "tablet").
    public var kind: String?

    public var id: Data { fingerprint }

    public var displayName: String {
        let trimmed = nickname?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? name : trimmed
    }

    public var shortId: String { BoardIdentity.shortId(fingerprint) }

    public init(name: String, hosts: [String], port: UInt16, fingerprint: Data, keyId: Data, pairKey: Data, lastUsedMs: Int64,
                nickname: String? = nil, favorite: Bool = false, kind: String? = nil) {
        self.name = name
        self.hosts = hosts
        self.port = port
        self.fingerprint = fingerprint
        self.keyId = keyId
        self.pairKey = pairKey
        self.lastUsedMs = lastUsedMs
        self.nickname = nickname
        self.favorite = favorite
        self.kind = kind
    }

    private enum CodingKeys: String, CodingKey {
        case name, hosts, port, fingerprint, keyId, pairKey, lastUsedMs, nickname, favorite, kind
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        hosts = try c.decode([String].self, forKey: .hosts)
        port = try c.decode(UInt16.self, forKey: .port)
        fingerprint = try c.decode(Data.self, forKey: .fingerprint)
        keyId = try c.decode(Data.self, forKey: .keyId)
        pairKey = try c.decode(Data.self, forKey: .pairKey)
        lastUsedMs = try c.decode(Int64.self, forKey: .lastUsedMs)
        nickname = try c.decodeIfPresent(String.self, forKey: .nickname)
        favorite = try c.decodeIfPresent(Bool.self, forKey: .favorite) ?? false
        kind = try c.decodeIfPresent(String.self, forKey: .kind)
    }
}

public struct SenderSettings: Codable, Equatable, Sendable {
    /// Balanced by default: school Wi-Fi rarely carries the best preset without freezes.
    public var preset = Preset.balanced
    /// Off by default: classrooms mostly share the picture, and the board's speakers may be loud.
    public var audioEnabled = false
    public var hevcEnabled = false
    public var overrides = StreamOverrides()

    public init() {}
}

/// Why a connection failed or ended, in the terms the screens explain to the teacher.
public enum MirrorFailure: String, Error, Codable, CaseIterable, Sendable {
    case unreachable
    case clientIsolation
    case refused
    case wrongReceiver
    case authFailed
    case wrongPin
    case receiverForgot
    case busyDenied
    case busyTimeout
    case rateLimited
    case lockedOut
    case incompatible
    case noEncoder
    case connectionLost
    case encoderFailed
    case stoppedByReceiver
    case receiverClosed
    case takenOver
    case broadcastEnded
    case cameraFailed
    case cameraDenied
    case cameraLeft
    case noTarget
    case internalError

    public static func from(rejectReason reason: UInt8) -> MirrorFailure {
        switch reason {
        case RejectReason.authFailed, RejectReason.sessionUnknown: return .authFailed
        case RejectReason.busyDenied: return .busyDenied
        case RejectReason.busyTimeout: return .busyTimeout
        case RejectReason.rateLimited: return .rateLimited
        case RejectReason.lockedOut: return .lockedOut
        case RejectReason.versionUnsupported, RejectReason.malformed: return .incompatible
        default: return .internalError
        }
    }

    /// Nil when this side stopped on purpose.
    public static func from(end: SessionEnd) -> MirrorFailure? {
        switch end {
        case .local(let reason):
            switch reason {
            case ByeReason.userStop: return nil
            case ByeReason.projectionStopped: return .broadcastEnded
            case ByeReason.error: return .encoderFailed
            default: return .connectionLost
            }
        case .remote(let reason):
            switch reason {
            case ByeReason.userStop: return .stoppedByReceiver
            case ByeReason.takeover: return .takenOver
            case ByeReason.receiverClosing: return .receiverClosed
            default: return .connectionLost
            }
        case .connectionLost: return .connectionLost
        case .encoderFailed: return .encoderFailed
        }
    }
}

public enum BroadcastPhase: String, Codable, Sendable {
    case idle
    case connecting
    case waitingApproval
    case starting
    case mirroring
    case ended
}

public enum AudioStatus: String, Codable, Sendable {
    case on
    case offSetting
    case offReceiver
    case unsupported
    case failed
    /// The camera sends picture only.
    case offCamera
}

public struct BroadcastStats: Codable, Equatable, Sendable {
    public var latencyMs: Int?
    public var fps: Double
    public var bitrateBps: Int64
    public var width: Int
    public var height: Int
    public var autoLowered: Bool
    public var rttMs: Int?
    public var lossPercent: Double
    public var quality: ConnectionQuality

    public init(_ stats: MirrorStats) {
        latencyMs = stats.latencyMs
        fps = stats.fps
        bitrateBps = stats.bitrateBps
        width = stats.width
        height = stats.height
        autoLowered = stats.autoLowered
        rttMs = stats.rttMs
        lossPercent = stats.lossPercent
        quality = stats.quality
    }

    private enum CodingKeys: String, CodingKey {
        case latencyMs, fps, bitrateBps, width, height, autoLowered, rttMs, lossPercent, quality
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        latencyMs = try c.decodeIfPresent(Int.self, forKey: .latencyMs)
        fps = try c.decode(Double.self, forKey: .fps)
        bitrateBps = try c.decode(Int64.self, forKey: .bitrateBps)
        width = try c.decode(Int.self, forKey: .width)
        height = try c.decode(Int.self, forKey: .height)
        autoLowered = try c.decodeIfPresent(Bool.self, forKey: .autoLowered) ?? false
        rttMs = try c.decodeIfPresent(Int.self, forKey: .rttMs)
        lossPercent = try c.decodeIfPresent(Double.self, forKey: .lossPercent) ?? 0
        quality = try c.decodeIfPresent(ConnectionQuality.self, forKey: .quality) ?? .good
    }
}

/// What the extension tells the app.
public struct BroadcastStatus: Codable, Equatable, Sendable {
    public var phase = BroadcastPhase.idle
    public var receiverName = ""
    public var paused = false
    public var reconnecting = false
    public var audio = AudioStatus.offSetting
    public var stats: BroadcastStats?
    public var failure: MirrorFailure?

    public init() {}
}

/// State shared by the app and the Broadcast Upload Extension, in JSON files in the App Group container. The app writes
/// everything except the status, which the extension writes. Pairing keys are covered by file protection.
public final class SharedStore: @unchecked Sendable {
    public static let appGroup = "group.com.sorumatik.casthop"
    private static let maxReceivers = 20

    private let directory: URL
    private let lock = NSLock()

    public init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// Nil if the App Group entitlement is missing from this build.
    public static func shared() -> SharedStore? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup).map {
            SharedStore(directory: $0.appendingPathComponent("CastHop", isDirectory: true))
        }
    }

    private func read<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func write<T: Encodable>(_ value: T, _ name: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        var options: Data.WritingOptions = [.atomic]
#if os(iOS)
        // The extension may start while the device is locked, after the first unlock.
        options.insert(.completeFileProtectionUntilFirstUserAuthentication)
#endif
        try? data.write(to: directory.appendingPathComponent(name), options: options)
    }

    /// Created once per installation (PROTOCOL.md §7.1 device_id).
    public var deviceId: Data {
        lock.lock()
        defer { lock.unlock() }
        if let id = read(Data.self, "device.json"), id.count == ControlCodec.idSize { return id }
        let id = Auth.randomBytes(ControlCodec.idSize)
        write(id, "device.json")
        return id
    }

    public var settings: SenderSettings {
        get { read(SenderSettings.self, "settings.json") ?? SenderSettings() }
        set { write(newValue, "settings.json") }
    }

    /// Favorites first, then latest used.
    public func receivers() -> [RememberedReceiver] {
        BoardIdentity.sortReceivers(read([RememberedReceiver].self, "receivers.json") ?? [])
    }

    public func find(prefix: Data) -> RememberedReceiver? {
        receivers().first { BoardIdentity.matchesPrefix(fingerprint: $0.fingerprint, prefix: prefix) }
    }

    public func recentIps(limit: Int = 4) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for r in receivers() {
            for h in r.hosts where BoardIdentity.isValidIpv4(h) && !seen.contains(h) {
                seen.insert(h)
                out.append(h)
                if out.count >= limit { return out }
            }
        }
        return out
    }

    /// Replaces an older record of the same receiver, preserving nickname, favorite and kind.
    public func save(_ receiver: RememberedReceiver) {
        lock.lock()
        defer { lock.unlock() }
        let current = read([RememberedReceiver].self, "receivers.json") ?? []
        let existing = current.first { $0.fingerprint == receiver.fingerprint }
        var merged = receiver
        merged.nickname = receiver.nickname ?? existing?.nickname
        merged.favorite = receiver.favorite || (existing?.favorite ?? false)
        merged.kind = receiver.kind ?? existing?.kind
        let others = current.filter { $0.fingerprint != receiver.fingerprint }
        let all = BoardIdentity.sortReceivers([merged] + others)
        write(Array(all.prefix(Self.maxReceivers)), "receivers.json")
    }

    public func setNickname(_ nickname: String?, fingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        let trimmed = nickname?.trimmingCharacters(in: .whitespacesAndNewlines)
        let clean = (trimmed?.isEmpty == false) ? String(trimmed!.prefix(48)) : nil
        let updated = (read([RememberedReceiver].self, "receivers.json") ?? []).map { r -> RememberedReceiver in
            var copy = r
            if copy.fingerprint == fingerprint { copy.nickname = clean }
            return copy
        }
        write(BoardIdentity.sortReceivers(updated), "receivers.json")
    }

    public func setFavorite(_ favorite: Bool, fingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        let updated = (read([RememberedReceiver].self, "receivers.json") ?? []).map { r -> RememberedReceiver in
            var copy = r
            if copy.fingerprint == fingerprint { copy.favorite = favorite }
            return copy
        }
        write(BoardIdentity.sortReceivers(updated), "receivers.json")
    }

    public func updateHosts(_ hosts: [String], port: UInt16, fingerprint: Data) {
        guard !hosts.isEmpty else { return }
        lock.lock()
        defer { lock.unlock() }
        let updated = (read([RememberedReceiver].self, "receivers.json") ?? []).map { r -> RememberedReceiver in
            var copy = r
            if copy.fingerprint == fingerprint {
                copy.hosts = hosts
                copy.port = port
            }
            return copy
        }
        write(BoardIdentity.sortReceivers(updated), "receivers.json")
    }

    public func touch(fingerprint: Data, nowMs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        let updated = (read([RememberedReceiver].self, "receivers.json") ?? []).map { r -> RememberedReceiver in
            var copy = r
            if copy.fingerprint == fingerprint { copy.lastUsedMs = nowMs }
            return copy
        }
        write(BoardIdentity.sortReceivers(updated), "receivers.json")
    }

    public func remove(fingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        let current = read([RememberedReceiver].self, "receivers.json") ?? []
        write(BoardIdentity.sortReceivers(current.filter { $0.fingerprint != fingerprint }), "receivers.json")
        writePreset(nil, fingerprint: fingerprint)
        if selectedFingerprint == fingerprint { selectedFingerprint = nil }
    }

    /// The preset the network last settled on with this receiver; nil when it kept the chosen one.
    public func preset(fingerprint: Data) -> Preset? {
        read([String: Preset].self, "presets.json")?[Self.hex(fingerprint)]
    }

    /// Nil forgets it. Only receivers still remembered keep an entry.
    public func savePreset(_ preset: Preset?, fingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        writePreset(preset, fingerprint: fingerprint)
    }

    private func writePreset(_ preset: Preset?, fingerprint: Data) {
        let known = Set((read([RememberedReceiver].self, "receivers.json") ?? []).map { Self.hex($0.fingerprint) })
        var all = (read([String: Preset].self, "presets.json") ?? [:]).filter { known.contains($0.key) }
        let key = Self.hex(fingerprint)
        all[key] = known.contains(key) ? preset : nil
        write(all, "presets.json")
    }

    private static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    /// The receiver the next broadcast goes to.
    public var selectedFingerprint: Data? {
        get { read(Data.self, "selected.json") }
        set {
            if let newValue {
                write(newValue, "selected.json")
            } else {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent("selected.json"))
            }
        }
    }

    public var selectedReceiver: RememberedReceiver? {
        guard let fingerprint = selectedFingerprint else { return nil }
        return receivers().first { $0.fingerprint == fingerprint }
    }

    public var status: BroadcastStatus {
        get { read(BroadcastStatus.self, "status.json") ?? BroadcastStatus() }
        set {
            write(newValue, "status.json")
            DarwinNotifier.post(.status)
        }
    }
}

/// Signals between the app and the extension. The payload, if any, is in the `SharedStore`.
public enum DarwinNotifier {
    public enum Name: String, Sendable {
        case status = "io.github.ozkanceng.casthop.broadcast.status"
        case stop = "io.github.ozkanceng.casthop.broadcast.stop"
        case pause = "io.github.ozkanceng.casthop.broadcast.pause"
        case unpause = "io.github.ozkanceng.casthop.broadcast.unpause"
    }

    fileprivate final class Box {
        let handler: () -> Void
        init(_ handler: @escaping () -> Void) { self.handler = handler }
    }

    /// Stops the delivery when released.
    public final class Observation {
        fileprivate let box: Box
        private let name: CFNotificationName

        fileprivate init(name: CFNotificationName, box: Box) {
            self.name = name
            self.box = box
        }

        deinit {
            CFNotificationCenterRemoveObserver(CFNotificationCenterGetDarwinNotifyCenter(),
                                               Unmanaged.passUnretained(box).toOpaque(), name, nil)
        }
    }

    public static func post(_ name: Name) {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(name.rawValue as CFString), nil, nil, true)
    }

    /// `handler` runs on the main thread.
    public static func observe(_ name: Name, handler: @escaping () -> Void) -> Observation {
        let box = Box(handler)
        let cfName = CFNotificationName(name.rawValue as CFString)
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(box).toOpaque(),
                                        { _, observer, _, _, _ in
            guard let observer else { return }
            Unmanaged<Box>.fromOpaque(observer).takeUnretainedValue().handler()
        }, cfName.rawValue, nil, .deliverImmediately)
        return Observation(name: cfName, box: box)
    }
}
