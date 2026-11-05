import CastHopProtocol
import CastHopTransport
import CoreMedia
import Foundation
import Network

public struct ReceiverOptions: Sendable {
    public var name = "CastHop"
    public var port: UInt16 = 47800
    /// macOS: identity and pairings live here. iOS and tvOS: nil, they live in the keychain.
    public var dataDirectory: URL?
    /// Instead of the addresses found on the machine.
    public var qrHosts: [String] = []
    public var pairingFile: URL?
    public var statsFile: URL?
    public var approvalTimeoutMs: Int64 = 20_000
    public var audio = true
    /// Decode without a window or audio device.
    public var headless = false
    public var displayWidth = 1920
    public var displayHeight = 1080
    public var displayRefresh = 60

    public init() {}
}

public enum ReceiverState: String, Sendable {
    case starting
    case waiting
    case connecting
    case streaming
    case paused
    case reconnecting
    case failed
}

public enum ReceiverFailure: Equatable, Sendable {
    case identity
    case noDecoder
    case portInUse(UInt16)
    case listener
}

public struct ReceiverStatsView: Equatable, Sendable {
    public var latencyMs: Int?
    public var fps: Double = 0
    public var bitrateKbps: Int = 0
    public var dropped: Int64 = 0
    public var decoder = ""
    public var hardware = false
    public var width = 0
    public var height = 0

    public init() {}
}

public struct TrustedDevice: Equatable, Identifiable, Sendable {
    public var id: Data
    public var name: String
}

/// Everything a screen needs. Holds the PIN and the QR, so it must never be logged.
public struct ReceiverSnapshot: Equatable, Sendable {
    public var state = ReceiverState.starting
    public var failure: ReceiverFailure?
    public var name = ""
    public var qrUri = ""
    public var pin = ""
    public var addresses: [String] = []
    public var port: UInt16 = 0
    public var senderName: String?
    public var pendingSenderName: String?
    public var stats = ReceiverStatsView()
    public var trusted: [TrustedDevice] = []
    public var audioEnabled = true

    public init() {}
}

/// The receiver: accepts connections, runs the session rules and feeds the decoders. Network, timers and session state
/// all stay on one serial queue; `onChange` reports each new snapshot there.
public final class ReceiverService: @unchecked Sendable {
    private static let tickMs = 250
    private static let reportMs = 500
    private static let maxUnauthenticated = 16

    private var options: ReceiverOptions
    public let queue = DispatchQueue(label: "casthop.receiver", qos: .userInteractive)
    public let video = VideoDecoder()
    private let audio: AudioPlayer

    private var identity: ReceiverIdentity?
    private var pairing: PairingManager?
    private var sessions: SessionManager?
    private var listener: NWListener?
    private var netInfo: NetInfo?
    private var connections: [ConnId: ReceiverConnection] = [:]
    private var nextId: ConnId = 0
    private var timers: [DispatchSourceTimer] = []
    private var trustedStore: TrustedStore?

    private var snapshot = ReceiverSnapshot()
    public var onChange: ((ReceiverSnapshot) -> Void)?

    private var sessionId: Data?
    private var sessionStartUs: UInt64 = 0
    private var firstFrameUs: UInt64?
    private var paused = false
    private var lastReportUs: UInt64 = 0
    private var sessionC2dSumUs: UInt64 = 0
    private var sessionC2dCount: UInt64 = 0
    private var sessionAudioLatencySumUs: UInt64 = 0
    private var sessionAudioLatencyCount: UInt64 = 0

    public init(options: ReceiverOptions) {
        self.options = options
        audio = AudioPlayer(headless: options.headless)
        snapshot.name = options.name
        snapshot.port = options.port
        snapshot.audioEnabled = options.audio
    }

    private static func monotonicMs() -> Int64 { Int64(MonotonicClock.nowUs() / 1000) }

    /// Starts listening. Failures show in the snapshot.
    public func start() {
        queue.async { [self] in startOnQueue() }
    }

    private func startOnQueue() {
        do {
#if os(macOS)
            let base = options.dataDirectory ?? FileManager.default.temporaryDirectory
            identity = try IdentityStore.loadOrCreate(directory: base.appendingPathComponent("identity"))
#else
            identity = try IdentityStore.loadOrCreate()
#endif
        } catch {
            fail(.identity)
            return
        }
        let pairing = PairingManager(nowMs: Self.monotonicMs())
        self.pairing = pairing
        let store = TrustedStore(directory: options.dataDirectory)
        trustedStore = store
        pairing.trustedSenders = store.load()
        pairing.onCredentialsChanged = { [weak self] in self?.updateCredentials() }
        pairing.onTrustedChanged = { [weak self] in
            guard let self, let pairing = self.pairing else { return }
            self.trustedStore?.save(pairing.trustedSenders)
            self.publish()
        }

        let info = receiverInfo()
        guard !info.videoDecoders.isEmpty else {
            fail(.noDecoder)
            return
        }
        sessions = SessionManager(pairing: pairing, info: info,
                                  policy: .init(approvalTimeoutMs: options.approvalTimeoutMs, resumeWindowMs: 10_000))

        video.onKeyframeNeeded = { [weak self] reason in
            self?.queue.async { self?.sendToControl(.keyframeRequest(reason: reason)) }
        }
        video.onFirstFrame = { [weak self] in
            self?.queue.async {
                guard let self, self.sessionId != nil, self.firstFrameUs == nil else { return }
                self.firstFrameUs = MonotonicClock.nowUs()
                self.refreshState()
            }
        }
        video.onSizeChanged = { [weak self] width, height in
            self?.queue.async {
                self?.snapshot.stats.width = width
                self?.snapshot.stats.height = height
                self?.publish()
            }
        }

        guard startListener() else { return }

        let net = NetInfo(queue: queue)
        net.onChange = { [weak self] in self?.updateCredentials() }
        netInfo = net
        net.start()
        updateCredentials()

        schedule(everyMs: Self.tickMs) { [weak self] in self?.tick() }
        schedule(everyMs: Self.reportMs) { [weak self] in self?.sendReport() }
        snapshot.state = .waiting
        publish()
    }

    private func schedule(everyMs: Int, _ body: @escaping () -> Void) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(everyMs), repeating: .milliseconds(everyMs), leeway: .milliseconds(20))
        timer.setEventHandler(handler: body)
        timer.resume()
        timers.append(timer)
    }

    private var listening = false

    private func startListener() -> Bool {
        guard let identity, let port = NWEndpoint.Port(rawValue: options.port) else {
            fail(.listener)
            return false
        }
        do {
            let l = try NWListener(using: TLSSettings.server(identity: identity.secIdentity), on: port)
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.listening = true
                    self.updateCredentials()
                case .failed(let error):
                    if case .posix(let code) = error, code == .EADDRINUSE {
                        self.fail(.portInUse(self.options.port))
                    } else {
                        self.fail(.listener)
                    }
                default:
                    break
                }
            }
            l.service = service()
            l.start(queue: queue)
            listener = l
            return true
        } catch {
            fail(.listener)
            return false
        }
    }

    private func service() -> NWListener.Service? {
        guard let identity else { return nil }
        let name = DiscoveryTxt.instanceName(options.name)
        let txt = NWTXTRecord(DiscoveryTxt.record(name: name, fingerprint: identity.fingerprint))
        return NWListener.Service(name: name, type: DiscoveryTxt.serviceType, txtRecord: txt)
    }

    private func fail(_ failure: ReceiverFailure) {
        snapshot.failure = failure
        snapshot.state = .failed
        publish()
    }

    private func receiverInfo() -> ReceiverInfo {
        var info = ReceiverInfo(name: options.name)
        info.displayWidth = UInt16(clamping: options.displayWidth)
        info.displayHeight = UInt16(clamping: options.displayHeight)
        info.displayRefresh = UInt8(clamping: options.displayRefresh)
        let hardware = VideoDecoder.hardwareH264 ? CodecSupport.flagHardware : 0
        info.videoDecoders = [CodecSupport(codec: Codec.h264, flags: CodecSupport.flagPreferred | hardware)]
        // A software H.265 decoder may not keep up, so it is not offered (PROTOCOL.md §11).
        if VideoDecoder.hardwareH265 {
            info.videoDecoders.append(CodecSupport(codec: Codec.h265, flags: CodecSupport.flagHardware))
        }
        if AudioPlayer.opusAvailable {
            info.audioDecoders.append(CodecSupport(codec: Codec.opus, flags: CodecSupport.flagPreferred))
        }
        if AudioPlayer.aacAvailable {
            info.audioDecoders.append(CodecSupport(codec: Codec.aacLc, flags: 0))
        }
        info.audioEnabled = options.audio && !info.audioDecoders.isEmpty
        return info
    }

    /// Ends any session and stops listening. Blocks until done, so a process may exit right after.
    public func shutdown() {
        queue.sync { [self] in
            if let sessions { apply(sessions.endSession(bye: ByeReason.receiverClosing)) }
            timers.forEach { $0.cancel() }
            timers = []
            listener?.cancel()
            netInfo?.stop()
            audio.stop()
        }
    }

    // MARK: Connections

    private func accept(_ nw: NWConnection) {
        let unauthenticated = connections.values.lazy.filter { $0.role == .unknown }.count
        guard unauthenticated < Self.maxUnauthenticated else {
            nw.forceCancel()
            return
        }
        identity?.prepare()
        nextId += 1
        let c = ReceiverConnection(id: nextId, connection: nw, queue: queue, receiverName: options.name)
        c.onControl = { [weak self] c, msg, recvUs in self?.onControl(c, msg, recvUs: recvUs) }
        c.onMedia = { [weak self] c, header, payload, recvUs in self?.onMedia(c, header, payload, recvUs: recvUs) }
        c.onClosed = { [weak self] c in self?.onClosed(c) }
        connections[c.id] = c
        c.start()
    }

    private func onControl(_ c: ReceiverConnection, _ msg: ControlMessage, recvUs: UInt64) {
        if c.role == .unknown {
            onFirstMessage(c, msg)
        } else {
            onSessionMessage(c, msg, recvUs: recvUs)
        }
    }

    private func onFirstMessage(_ c: ReceiverConnection, _ msg: ControlMessage) {
        guard let sessions, let identity else { return }
        switch msg {
        case .hello(let hello):
            let a = sessions.onHello(conn: c.id, hello: hello, serverNonce: c.serverNonce,
                                     certFingerprint: identity.fingerprint, nowMs: Self.monotonicMs())
            if sessions.isControl(c.id) {
                c.setRole(.control)
            } else if sessions.pending?.conn == c.id {
                c.setRole(.pending)
            }
            apply(a)
        case .resume(let resume):
            let a = sessions.onResume(conn: c.id, resume: resume, serverNonce: c.serverNonce)
            if sessions.isControl(c.id) { c.setRole(.control) }
            apply(a)
        case .attach(let attach):
            let a = sessions.onAttach(conn: c.id, attach: attach, serverNonce: c.serverNonce)
            if sessions.mediaSlot(of: c.id) != .none { c.setRole(.media) }
            apply(a)
        default:
            c.send(.reject(reason: RejectReason.malformed, retryAfterMs: nil))
            c.closeGracefully()
        }
    }

    private func onSessionMessage(_ c: ReceiverConnection, _ msg: ControlMessage, recvUs: UInt64) {
        guard let sessions else { return }
        if case .bye = msg {
            apply(sessions.onBye(conn: c.id))
            return
        }
        guard sessions.isControl(c.id) else { return } // nothing else is expected while waiting for approval
        switch msg {
        case .ping(let id, let t0, let offset):
            c.send(.pong(pingId: id, t0Us: t0, t1Us: recvUs, t2Us: MonotonicClock.nowUs()))
            if let offset {
                video.setClockOffset(offset)
                audio.setClockOffset(offset)
            }
        case .pause:
            paused = true
            refreshState()
        case .unpause:
            paused = false
            refreshState()
        default:
            break
        }
    }

    private func onMedia(_ c: ReceiverConnection, _ header: FrameHeader, _ payload: Data, recvUs: UInt64) {
        switch sessions?.mediaSlot(of: c.id) {
        case .video where header.type == .video: video.push(header: header, payload: payload, recvUs: recvUs)
        case .audio where header.type == .audio: audio.push(header: header, payload: payload, recvUs: recvUs)
        default: break
        }
    }

    private func onClosed(_ c: ReceiverConnection) {
        connections[c.id] = nil
        if let sessions { apply(sessions.onConnectionClosed(conn: c.id, nowMs: Self.monotonicMs())) }
    }

    private func apply(_ actions: Actions) {
        for (id, msg) in actions.send { connections[id]?.send(msg) }
        for id in actions.close { connections[id]?.closeGracefully() }
        refreshState()
    }

    private func sendToControl(_ msg: ControlMessage) {
        if let id = sessions?.session?.control { connections[id]?.send(msg) }
    }

    // MARK: State

    private func refreshState() {
        guard let sessions else { return }
        let s = sessions.session
        if s?.sessionId != sessionId {
            if sessionId != nil { finishSessionStats() }
            sessionId = s?.sessionId
            video.reset()
            audio.reset()
            firstFrameUs = nil
            paused = false
            sessionC2dSumUs = 0
            sessionC2dCount = 0
            sessionAudioLatencySumUs = 0
            sessionAudioLatencyCount = 0
            lastReportUs = MonotonicClock.nowUs()
            sessionStartUs = lastReportUs
            snapshot.stats = ReceiverStatsView()
        }
        var next = ReceiverState.waiting
        if snapshot.state == .failed {
            next = .failed
        } else if let s {
            if s.control == nil {
                next = .reconnecting
            } else if paused {
                next = .paused
            } else {
                next = firstFrameUs != nil ? .streaming : .connecting
            }
        }
        snapshot.state = next
        snapshot.senderName = s?.deviceName
        snapshot.pendingSenderName = sessions.pending?.hello.deviceName
        publish()
    }

    private func publish() {
        if let pairing {
            snapshot.pin = pairing.pin
            snapshot.trusted = pairing.trustedSenders.map { TrustedDevice(id: $0.keyId, name: $0.deviceName) }
        }
        onChange?(snapshot)
    }

    private func tick() {
        guard let sessions else { return }
        let now = Self.monotonicMs()
        pairing?.tick(nowMs: now)
        let a = sessions.tick(nowMs: now)
        if !a.isEmpty { apply(a) }
        let nowUs = MonotonicClock.nowUs()
        // The check may close connections, which changes the dictionary.
        for c in Array(connections.values) { c.checkDeadline(nowUs: nowUs) }
    }

    private func sendReport() {
        let now = MonotonicClock.nowUs()
        let intervalUs = max(now - lastReportUs, 1)
        lastReportUs = now
        let st = video.takeStats()
        let at = audio.takeStats()
        // Lip sync: audio plays when the picture reaches the screen, one frame after it is drawn.
        if st.c2dCount > 0 {
            let frameUs = UInt64(1_000_000 / max(options.displayRefresh, 1))
            audio.setTargetLatency(videoUs: st.c2dSumUs / UInt64(st.c2dCount) + frameUs)
        }
        guard sessions?.session?.control != nil else { return }

        let c2d = st.c2dCount > 0 ? UInt32(clamping: st.c2dSumUs / UInt64(st.c2dCount)) : 0
        sendToControl(.receiverReport(ReceiverReport(
            intervalMs: UInt16(clamping: intervalUs / 1000), framesReceived: st.framesReceived, framesRendered: st.framesRendered,
            framesDropped: st.framesDropped, bytesReceived: UInt32(clamping: st.bytesReceived),
            c2rAvgUs: st.c2rCount > 0 ? UInt32(clamping: st.c2rSumUs / UInt64(st.c2rCount)) : 0,
            c2rMinUs: st.c2rMinUs, c2rMaxUs: st.c2rMaxUs, c2dAvgUs: c2d, decodeBacklog: st.backlog,
            audioLate: UInt16(clamping: at.late))))
        sessionAudioLatencySumUs += at.latencySumUs
        sessionAudioLatencyCount += at.latencyCount
        sessionC2dSumUs += st.c2dSumUs
        sessionC2dCount += UInt64(st.c2dCount)

        snapshot.stats.latencyMs = st.c2dCount > 0 ? Int(c2d / 1000) : nil
        snapshot.stats.fps = Double(st.framesRendered) * 1e6 / Double(intervalUs)
        snapshot.stats.bitrateKbps = Int(Double(st.bytesReceived) * 8e3 / Double(intervalUs))
        snapshot.stats.dropped = video.totalDropped
        snapshot.stats.decoder = video.decoderName
        snapshot.stats.hardware = video.hardwareDecoding
        publish()
    }

    private func finishSessionStats() {
        guard let file = options.statsFile, let sessionId else { return }
        video.finish()
        func ms(_ sum: UInt64, _ count: UInt64) -> Any { count > 0 ? Double(sum) / Double(count) / 1000 : NSNull() }
        let record: [String: Any] = [
            "session_id": sessionId.hex,
            "frames_received": video.totalReceived,
            "frames_rendered": video.totalRendered,
            "frames_dropped": video.totalDropped,
            "first_frame_ms": firstFrameUs.map { Double($0 - sessionStartUs) / 1000 } ?? NSNull(),
            "c2d_avg_ms": ms(sessionC2dSumUs, sessionC2dCount),
            "decoder": video.decoderName,
            "hardware": video.hardwareDecoding,
            "audio_received": audio.totalReceived,
            "audio_played": audio.totalPlayed,
            "audio_late": audio.totalLate,
            "audio_latency_ms": ms(sessionAudioLatencySumUs, sessionAudioLatencyCount),
        ]
        guard var line = try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]) else { return }
        line.append(0x0A)
        if !FileManager.default.fileExists(atPath: file.path) {
            FileManager.default.createFile(atPath: file.path, contents: nil)
        }
        if let handle = try? FileHandle(forWritingTo: file) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: line)
        }
    }

    private func updateCredentials() {
        guard let pairing, let identity else { return }
        var hosts = options.qrHosts.isEmpty ? (netInfo?.addresses ?? []) : options.qrHosts
        hosts = Array(hosts.prefix(QrPayload.maxHosts))
        snapshot.addresses = hosts
        snapshot.qrUri = hosts.isEmpty ? "" : QrPayload(hosts: hosts, port: options.port, token: pairing.qrToken,
                                                        certFingerprint: identity.fingerprint, name: options.name).toUri()
        writePairingFile()
        publish()
    }

    private func writePairingFile() {
        // A test reads this file and connects at once, so it must not appear before the port is bound.
        guard listening, let file = options.pairingFile, let pairing, let identity else { return }
        let record: [String: Any] = ["uri": snapshot.qrUri, "pin": pairing.pin, "port": Int(options.port),
                                     "fingerprint": identity.fingerprint.hex, "token": Base64Url.encode(pairing.qrToken)]
        guard let data = try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]) else { return }
        try? FileStore.writePrivate(data, to: file)
    }

    // MARK: Commands from the screen

    public func approvePending() {
        queue.async { [self] in
            guard let sessions, let id = sessions.pending?.conn else { return }
            let a = sessions.resolvePending(accept: true)
            if sessions.isControl(id) { connections[id]?.setRole(.control) }
            apply(a)
        }
    }

    public func denyPending() {
        queue.async { [self] in
            if let sessions { apply(sessions.resolvePending(accept: false)) }
        }
    }

    public func stopSession() {
        queue.async { [self] in
            if let sessions { apply(sessions.endSession(bye: ByeReason.userStop)) }
        }
    }

    public func forgetSender(keyId: Data) {
        queue.async { [self] in pairing?.removeTrusted(keyId: keyId) }
    }

    public func setReceiverName(_ name: String) {
        queue.async { [self] in
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, trimmed != options.name else { return }
            options.name = trimmed
            snapshot.name = trimmed
            sessions?.receiverInfo = receiverInfo()
            listener?.service = service()
            updateCredentials()
        }
    }

    public func setAudioEnabled(_ on: Bool) {
        queue.async { [self] in
            guard on != options.audio else { return }
            options.audio = on
            snapshot.audioEnabled = on
            sessions?.receiverInfo = receiverInfo()
            publish()
        }
    }

    /// Tells senders the real display size, as WELCOME does.
    public func setDisplay(width: Int, height: Int, refresh: Int) {
        queue.async { [self] in
            options.displayWidth = width
            options.displayHeight = height
            options.displayRefresh = refresh
            sessions?.receiverInfo = receiverInfo()
        }
    }
}

/// The list of paired senders: a private file if there is a data directory, otherwise the keychain.
final class TrustedStore {
    private struct Record: Codable {
        var key_id: String
        var pair_key: String
        var device_id: String
        var device_name: String
    }

    private static let account = "trusted-senders"
    private let file: URL?

    init(directory: URL?) {
        file = directory?.appendingPathComponent("trusted.json")
        if let directory {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                     attributes: [.posixPermissions: 0o700])
        }
    }

    func load() -> [TrustedSender] {
        let data = file.flatMap { try? Data(contentsOf: $0) } ?? (file == nil ? KeychainBlob.read(account: Self.account) : nil)
        guard let data, let records = try? JSONDecoder().decode([Record].self, from: data) else { return [] }
        return records.compactMap { r in
            guard let keyId = Data(hex: r.key_id), keyId.count == ControlCodec.idSize,
                  let pairKey = Data(hex: r.pair_key), pairKey.count == ControlCodec.keySize,
                  let deviceId = Data(hex: r.device_id), deviceId.count == ControlCodec.idSize else { return nil }
            return TrustedSender(keyId: keyId, pairKey: pairKey, deviceId: deviceId, deviceName: r.device_name)
        }
    }

    func save(_ list: [TrustedSender]) {
        let records = list.map { Record(key_id: $0.keyId.hex, pair_key: $0.pairKey.hex, device_id: $0.deviceId.hex,
                                        device_name: $0.deviceName) }
        guard let data = try? JSONEncoder().encode(records) else { return }
        if let file {
            try? FileStore.writePrivate(data, to: file)
        } else {
            try? KeychainBlob.write(data, account: Self.account)
        }
    }
}
