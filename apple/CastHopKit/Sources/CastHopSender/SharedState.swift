import CastHopProtocol
import Foundation

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

    public var id: Data { fingerprint }

    public init(name: String, hosts: [String], port: UInt16, fingerprint: Data, keyId: Data, pairKey: Data, lastUsedMs: Int64) {
        self.name = name
        self.hosts = hosts
        self.port = port
        self.fingerprint = fingerprint
        self.keyId = keyId
        self.pairKey = pairKey
        self.lastUsedMs = lastUsedMs
    }
}

public struct SenderSettings: Codable, Equatable, Sendable {
    public var preset = Preset.best
    /// Off by default: classrooms mostly share the picture, and the board's speakers may be loud.
    public var audioEnabled = false
    public var hevcEnabled = false
    public var overrides = StreamOverrides()

    public init() {}
}

/// Why a connection failed or ended, in the terms the screens explain to the teacher.
public enum MirrorFailure: String, Error, Codable, CaseIterable, Sendable {
    case unreachable
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

    public init(_ stats: MirrorStats) {
        latencyMs = stats.latencyMs
        fps = stats.fps
        bitrateBps = stats.bitrateBps
        width = stats.width
        height = stats.height
        autoLowered = stats.autoLowered
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

    /// Latest first.
    public func receivers() -> [RememberedReceiver] {
        (read([RememberedReceiver].self, "receivers.json") ?? []).sorted { $0.lastUsedMs > $1.lastUsedMs }
    }

    /// Replaces an older record of the same receiver.
    public func save(_ receiver: RememberedReceiver) {
        lock.lock()
        defer { lock.unlock() }
        let others = receivers().filter { $0.fingerprint != receiver.fingerprint }
        let all = ([receiver] + others).sorted { $0.lastUsedMs > $1.lastUsedMs }
        write(Array(all.prefix(Self.maxReceivers)), "receivers.json")
    }

    public func touch(fingerprint: Data, nowMs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        write(receivers().map { r in
            var r = r
            if r.fingerprint == fingerprint { r.lastUsedMs = nowMs }
            return r
        }, "receivers.json")
    }

    public func remove(fingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        write(receivers().filter { $0.fingerprint != fingerprint }, "receivers.json")
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
        let known = Set(receivers().map { Self.hex($0.fingerprint) })
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
