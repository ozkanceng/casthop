import CastHopProtocol
import CastHopTransport
import Foundation

/// The encoder as `MirrorSession` sees it. The first frame out must be CONFIG (Annex-B parameter sets).
public protocol VideoSource: AnyObject {
    func start(plan: StreamPlan, sink: @escaping (EncodedFrame) -> Void, onError: @escaping (Error) -> Void) throws
    func requestKeyframe()
    func setBitrate(_ bps: Int64)
    func stop()
}

/// Audio as `MirrorSession` sees it. `config` is the CONFIG payload without the codec byte.
public protocol AudioSource: AnyObject {
    var codec: UInt8 { get }
    var sampleRate: Int { get }
    var channels: Int { get }
    var config: Data { get }
    func start(sink: @escaping (AudioPacket) -> Void, onError: @escaping (Error) -> Void) throws
    func stop()
}

public struct MirrorStats: Equatable, Sendable {
    public var latencyMs: Int?
    public var fps: Double
    public var bitrateBps: Int64
    public var droppedFrames: Int64
    public var width: Int
    public var height: Int
    /// Quality is below the chosen preset because the network cannot carry it.
    public var autoLowered: Bool
}

public enum LinkState: Sendable {
    case connected
    case reconnecting
}

public enum SessionEnd: Sendable {
    case local(byeReason: UInt8)
    case remote(byeReason: UInt8)
    /// The link broke and did not return in the RESUME window. `sessionExpired`: the receiver forgot the session.
    case connectionLost(sessionExpired: Bool)
    case encoderFailed
}

/// The sender side of an accepted session (PROTOCOL.md §4–7): CONTROL handling, one PING and SENDER_STATS per second,
/// bitrate adaptation every 250 ms, queued VIDEO with frame dropping, liveness and RESUME, encoder restarts and automatic
/// preset steps. `start` runs as soon as HELLO is accepted, `startVideo` once capture is ready.
public final class MirrorSession: @unchecked Sendable {
    public struct Config: Sendable {
        public var pingIntervalMs = 1000
        public var livenessTimeoutMs = 2500
        /// The receiver keeps a session for 10 s: give up a little earlier.
        public var resumeWindowMs = 9500
        public var maxEncoderRestarts = 3
        /// Above this capture→receive delay, even the lowest bitrate is too much: the path is drained.
        public var latencyBudgetUs: Int64 = 300_000
        public var quality = QualityGovernor.Config()

        public init() {}
    }

    public var onStats: ((MirrorStats) -> Void)?
    public var onEnd: ((SessionEnd) -> Void)?
    public var onLink: ((LinkState) -> Void)?
    /// Audio stopped or could not return. The picture goes on.
    public var onAudioIssue: ((Error) -> Void)?
    /// A lower plan after repeated encoder failures.
    public var fallbackPlan: ((StreamPlan) -> StreamPlan?)?
    /// Applies a step: resizes the source and calls `reconfigure`. False if there is nothing to move to.
    public var qualityStep: ((QualityStep) -> Bool)?

    private let config: Config
    /// Owns all state below. The video and audio writer threads touch only what is marked as such.
    private let queue = DispatchQueue(label: "casthop.mirror", qos: .userInteractive)
    private let lock = NSLock()
    private var session: Session
    private var clock = ClockSync()
    private var ended = false
    private var timers: [DispatchSourceTimer] = []
    private var source: VideoSource?
    private var audioSource: AudioSource?
    private var video: MediaChannel?
    private var audio: MediaChannel?
    private var plan: StreamPlan?
    private var bitrate: BitrateController?
    private let quality: QualityGovernor
    private var qualityStepsDown = 0
    private let keyframeGate = KeyframeGate()
    private var lastConfig: Data?
    private var lastReport: ReceiverReport?
    private var lastRxUs = MonotonicClock.nowUs()
    private var linkEpoch = 0
    private var encoderRestarts = 0
    private var pingId: UInt32 = 0
    private var lastKeyframeRequestUs: UInt64 = 0
    private var drainUntilUs: UInt64 = 0
    private var drainCooldownUntilUs: UInt64 = 0
    private var framesSent: Int64 = 0
    private var statsFramesAtLastTick: Int64 = 0
    private var statsLastTickUs: UInt64 = 0
    private var videoThreadStarted = false
    private var audioThreadStarted = false
    private var resumes = 0
    private var drains = 0
    private var link = LinkState.connected
    private var isPaused = false
    private let audioQueue = AudioQueue()
    private var sendQueue: SendQueue!

    private static let bitrateTickMs = 250
    private static let fastPings = 4
    private static let fastPingIntervalMs = 50
    private static let statsIntervalMs = 1000
    private static let keyframeCoalesceUs: UInt64 = 200_000

    public init(session: Session, config: Config = Config()) {
        self.session = session
        self.config = config
        quality = QualityGovernor(config: config.quality)
        sendQueue = SendQueue(onDrop: { [weak self] _ in self?.onQueueDrop() })
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    public var welcome: Welcome { locked { session.welcome } }
    public var linkState: LinkState { locked { link } }
    public var paused: Bool { locked { isPaused } }
    public var resumeCount: Int { locked { resumes } }
    public var drainCount: Int { locked { drains } }
    public var audioDropped: Int64 { audioQueue.dropped }

    public func start() {
        listen(to: session.control, epoch: 0)
        // Early PINGs are frequent so the clock offset settles within about 150 ms.
        for i in 0..<Self.fastPings {
            queue.asyncAfter(deadline: .now() + .milliseconds(i * Self.fastPingIntervalMs)) { [weak self] in self?.ping() }
        }
        schedule(everyMs: config.pingIntervalMs) { [weak self] in self?.ping() }
        schedule(everyMs: config.livenessTimeoutMs / 5) { [weak self] in self?.checkLiveness() }
    }

    private func schedule(everyMs: Int, _ body: @escaping () -> Void) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(everyMs), repeating: .milliseconds(everyMs), leeway: .milliseconds(10))
        timer.setEventHandler(handler: body)
        timer.resume()
        timers.append(timer)
    }

    private func run<T>(_ body: @escaping () throws -> T) throws -> T {
        try queue.sync(execute: body)
    }

    /// Opens VIDEO and starts the encoder.
    public func startVideo(plan: StreamPlan, source: VideoSource) async throws {
        let s = locked { session }
        let channel = try await SenderClient.attach(session: s, kind: StreamKind.video)
        queue.sync {
            self.plan = plan
            self.source = source
            bitrate = BitrateController(config: .init(initialBps: plan.startBps, minBps: plan.minBps, maxBps: plan.maxBps))
            locked { video = channel }
            sendStreamConfig(plan)
            statsLastTickUs = MonotonicClock.nowUs()
            schedule(everyMs: Self.bitrateTickMs) { [weak self] in self?.bitrateTick() }
            schedule(everyMs: Self.statsIntervalMs) { [weak self] in self?.statsTick() }
            startThread(name: "casthop.video") { [weak self] in self?.sendLoop() }
            startSource(plan)
        }
    }

    /// Starts audio if the receiver takes it. A failure here leaves the picture running.
    public func startAudio(_ src: AudioSource) async throws {
        let s = locked { session }
        guard s.welcome.audioEnabled, s.welcome.audioDecoders.contains(where: { $0.codec == src.codec }) else {
            throw ClientError.unexpectedMessage
        }
        try await attachAudio(s, src)
        queue.sync {
            audioSource = src
            startThread(name: "casthop.audio") { [weak self] in self?.audioSendLoop() }
        }
        try src.start(sink: { [weak self] in self?.audioQueue.offer($0) }, onError: { [weak self] error in
            guard let self else { return }
            self.queue.async {
                self.audioQueue.close()
                self.locked { self.audio }?.close()
                self.locked { self.audio = nil }
                self.onAudioIssue?(error)
            }
        })
    }

    private func startThread(name: String, _ body: @escaping () -> Void) {
        let t = Thread(block: body)
        t.name = name
        t.qualityOfService = .userInteractive
        t.start()
    }

    private func attachAudio(_ s: Session, _ src: AudioSource) async throws {
        let channel = try await SenderClient.attach(session: s, kind: StreamKind.audio)
        s.control.send(.streamConfig(StreamConfig(streamKind: StreamKind.audio, codec: src.codec,
                                                  sampleRate: UInt32(src.sampleRate), channels: UInt8(src.channels))))
        var payload = Data([src.codec])
        payload.append(src.config)
        try channel.send(flags: FrameFlags.config, ptsUs: MonotonicClock.nowUs(), payload: payload)
        locked { audio = channel }
    }

    /// The source size changed: the encoder restarts with the new format and sends a new CONFIG and IDR.
    public func reconfigure(_ newPlan: StreamPlan) {
        queue.async { [self] in
            guard !isEnded, let src = source else { return }
            src.stop()
            let current = bitrate?.appliedBps ?? newPlan.startBps
            locked { plan = newPlan }
            bitrate = BitrateController(config: .init(initialBps: min(max(current, newPlan.minBps), newPlan.maxBps),
                                                      minBps: newPlan.minBps, maxBps: newPlan.maxBps))
            sendStreamConfig(newPlan)
            startSource(newPlan)
        }
    }

    /// The receiver shows "paused". The caller must stop feeding frames.
    public func pause() {
        queue.async { [self] in
            guard !isEnded, !paused else { return }
            locked { isPaused = true }
            controlSend(.pause)
        }
    }

    /// The first frame after a pause is an IDR (PROTOCOL.md §7.2).
    public func unpause() {
        queue.async { [self] in
            guard !isEnded, paused else { return }
            locked { isPaused = false }
            controlSend(.unpause)
            forceKeyframe()
        }
    }

    public func stop(byeReason: UInt8) {
        queue.async { [self] in end(.local(byeReason: byeReason)) }
    }

    private var isEnded: Bool { locked { ended } }

    // MARK: Source and encoder errors

    private func startSource(_ p: StreamPlan) {
        guard let src = source else { return }
        do {
            try src.start(plan: p, sink: { [weak self] frame in
                self?.sendQueue.offer(frame, nowUs: MonotonicClock.nowUs())
            }, onError: { [weak self] error in self?.onSourceError(error) })
        } catch {
            onSourceError(error)
        }
    }

    private func onSourceError(_ error: Error) {
        queue.async { [self] in
            guard !isEnded, let src = source, let current = locked({ plan }) else { return }
            encoderRestarts += 1
            src.stop()
            let next = encoderRestarts <= config.maxEncoderRestarts ? current : fallbackPlan?(current)
            guard let next, encoderRestarts <= config.maxEncoderRestarts * 2 else {
                end(.encoderFailed)
                return
            }
            if next != current {
                locked { plan = next }
                sendStreamConfig(next)
            }
            startSource(next)
        }
    }

    // MARK: Link and RESUME

    private func listen(to control: ControlChannel, epoch: Int) {
        control.listen(onMessage: { [weak self] msg in
            self?.queue.async { self?.handle(msg, epoch: epoch) }
        }, onClosed: { [weak self] _ in
            self?.queue.async { self?.linkLost(epoch: epoch) }
        })
    }

    private func handle(_ msg: ControlMessage, epoch: Int) {
        guard !isEnded, epoch == linkEpoch else { return }
        let now = MonotonicClock.nowUs()
        lastRxUs = now
        switch msg {
        case .pong(_, let t0, let t1, let t2):
            _ = clock.onPong(t0Us: Int64(t0), t1Us: Int64(t1), t2Us: Int64(t2), t3Us: Int64(now))
        case .keyframeRequest:
            requestKeyframe()
        case .receiverReport(let report):
            lastReport = report
            bitrate?.onReceiverReport(nowMs: Int64(now / 1000), report: report)
            checkLatencyBudget(report)
        case .bye(let reason):
            end(.remote(byeReason: reason))
        default:
            break
        }
    }

    private func checkLiveness() {
        guard linkState == .connected else { return }
        if MonotonicClock.nowUs() - lastRxUs > UInt64(config.livenessTimeoutMs) * 1000 { linkLost(epoch: linkEpoch) }
    }

    private func linkLost(epoch: Int) {
        guard !isEnded, epoch == linkEpoch, linkState == .connected else { return }
        linkEpoch += 1
        let old: Session = locked {
            link = .reconnecting
            let v = video
            let a = audio
            video = nil
            audio = nil
            v?.close()
            a?.close()
            return session
        }
        old.control.abort()
        onLink?(.reconnecting)
        Task { [weak self] in await self?.reconnect(old) }
    }

    private func reconnect(_ old: Session) async {
        let deadline = MonotonicClock.nowUs() + UInt64(config.resumeWindowMs) * 1000
        var backoffMs: UInt64 = 250
        while !isEnded && MonotonicClock.nowUs() < deadline {
            do {
                switch try await SenderClient.resume(session: old) {
                case .rejected(let reason, _):
                    queue.async { [self] in end(.connectionLost(sessionExpired: reason == RejectReason.sessionUnknown)) }
                    return
                case .accepted(let fresh):
                    if isEnded {
                        fresh.control.abort()
                        return
                    }
                    await onResumed(fresh)
                    return
                }
            } catch ClientError.unexpectedMessage, ClientError.unsupportedVersion {
                queue.async { [self] in end(.connectionLost(sessionExpired: false)) }
                return
            } catch {
                // The network is not back yet.
            }
            try? await Task.sleep(nanoseconds: backoffMs * 1_000_000)
            backoffMs = min(backoffMs * 2, 1000)
        }
        queue.async { [self] in end(.connectionLost(sessionExpired: false)) }
    }

    private func onResumed(_ fresh: Session) async {
        let (src, audioSrc, p, cfg, epoch): (VideoSource?, AudioSource?, StreamPlan?, Data?, Int) = queue.sync {
            locked { session = fresh }
            lastRxUs = MonotonicClock.nowUs()
            listen(to: fresh.control, epoch: linkEpoch)
            return (source, audioSource, locked { plan }, locked { lastConfig }, linkEpoch)
        }
        if src != nil {
            do {
                let channel = try await SenderClient.attach(session: fresh, kind: StreamKind.video)
                if let p, let cfg { try channel.send(flags: FrameFlags.config, ptsUs: MonotonicClock.nowUs(), payload: withCodec(p, cfg)) }
                queue.sync {
                    if let p { sendStreamConfig(p) }
                    locked { video = channel }
                }
            } catch {
                queue.sync {
                    locked { link = .connected }
                    linkLost(epoch: epoch)
                }
                return
            }
        }
        if let audioSrc {
            do {
                try await attachAudio(fresh, audioSrc)
            } catch {
                onAudioIssue?(error)
            }
        }
        queue.sync {
            // Frames gathered during the break are stale: the first frame is a new IDR.
            sendQueue.dropUntilKeyframe()
            locked {
                link = .connected
                resumes += 1
            }
            for i in 1...2 {
                queue.asyncAfter(deadline: .now() + .milliseconds(i * Self.fastPingIntervalMs)) { [weak self] in self?.ping() }
            }
            if paused { controlSend(.pause) } else { forceKeyframe() }
            onLink?(.connected)
        }
    }

    // MARK: Sending

    private func sendLoop() {
        while !isEnded {
            guard let channel = locked({ video }) else {
                // Reconnecting: the queue fills, drops P-frames and waits for an IDR.
                Thread.sleep(forTimeInterval: 0.005)
                continue
            }
            let drainUntil = locked { drainUntilUs }
            if drainUntil != 0 {
                if MonotonicClock.nowUs() < drainUntil {
                    Thread.sleep(forTimeInterval: 0.005)
                    continue
                }
                locked { drainUntilUs = 0 }
                sendQueue.dropUntilKeyframe()
                queue.async { [weak self] in self?.forceKeyframe() }
            }
            guard let (frame, discontinuity) = sendQueue.take(timeoutMs: 100) else { continue }
            var flags = frame.flags
            if discontinuity { flags |= FrameFlags.discontinuity }
            var payload = frame.data
            if frame.isConfig {
                locked { lastConfig = frame.data }
                guard let p = locked({ plan }) else { continue }
                payload = withCodec(p, frame.data)
            }
            if frame.isKeyframe { keyframeGate.onWriteStarted(nowUs: MonotonicClock.nowUs()) }
            do {
                try channel.send(flags: flags, ptsUs: frame.ptsUs, payload: payload)
                if !frame.isConfig { locked { framesSent += 1 } }
                let delay = sendQueue.oldestAgeUs(nowUs: MonotonicClock.nowUs())
                queue.async { [weak self] in self?.bitrate?.onSenderQueueDelay(delay) }
            } catch {
                queue.async { [weak self] in
                    guard let self else { return }
                    self.linkLost(epoch: self.linkEpoch)
                }
            }
            if frame.isKeyframe && keyframeGate.onWriteEnded() {
                queue.async { [weak self] in self?.forceKeyframe() }
            }
        }
    }

    private func audioSendLoop() {
        while !isEnded {
            guard let channel = locked({ audio }) else {
                Thread.sleep(forTimeInterval: 0.005)
                audioQueue.clear() // audio gathered during a break is stale
                continue
            }
            guard let packet = audioQueue.take(timeoutMs: 100) else { continue }
            if paused { continue } // pausing is for privacy: audio is not shared either
            do {
                try channel.send(flags: 0, ptsUs: packet.ptsUs, payload: packet.data)
            } catch {
                queue.async { [weak self] in
                    guard let self else { return }
                    self.linkLost(epoch: self.linkEpoch)
                }
            }
        }
    }

    private func withCodec(_ p: StreamPlan, _ config: Data) -> Data {
        var out = Data([p.codec])
        out.append(config)
        return out
    }

    private func controlSend(_ msg: ControlMessage) {
        guard linkState == .connected else { return }
        locked { session }.control.send(msg)
    }

    private func sendStreamConfig(_ p: StreamPlan) {
        locked { session }.control.send(.streamConfig(StreamConfig(
            streamKind: StreamKind.video, codec: p.codec, width: UInt16(clamping: p.width), height: UInt16(clamping: p.height),
            fps: UInt8(clamping: p.fps), bitrateBps: UInt32(clamping: bitrate?.appliedBps ?? p.startBps))))
    }

    private func checkLatencyBudget(_ report: ReceiverReport) {
        let now = MonotonicClock.nowUs()
        // A still screen has nothing queued to drain; one late redraw is not a backlog.
        guard report.framesReceived >= BitrateController.idleReportFrames,
              Int64(report.c2rAvgUs) > config.latencyBudgetUs, now >= drainCooldownUntilUs else { return }
        // Wait until the data in the path has drained: what is over budget, at most 1 s.
        let drainUs = UInt64(min(max(Int64(report.c2rAvgUs) - config.latencyBudgetUs / 3, 50_000), 1_000_000))
        locked {
            drainUntilUs = now + drainUs
            drains += 1
        }
        // Reports during the drain describe old frames: wait one more report interval.
        drainCooldownUntilUs = now + drainUs + 600_000
        sendQueue.dropUntilKeyframe()
        bitrate?.onSenderDrop()
    }

    private func onQueueDrop() {
        queue.async { [weak self] in
            self?.bitrate?.onSenderDrop()
            self?.requestKeyframe()
        }
    }

    /// Requests closer than 200 ms make one IDR (PROTOCOL.md §7.2).
    private func requestKeyframe() {
        let now = MonotonicClock.nowUs()
        guard keyframeGate.onRequest(nowUs: now), now - lastKeyframeRequestUs >= Self.keyframeCoalesceUs else { return }
        lastKeyframeRequestUs = now
        source?.requestKeyframe()
    }

    private func forceKeyframe() {
        lastKeyframeRequestUs = MonotonicClock.nowUs()
        source?.requestKeyframe()
    }

    private func ping() {
        guard linkState == .connected else { return }
        pingId &+= 1
        locked { session }.control.send(.ping(pingId: pingId, t0Us: MonotonicClock.nowUs(), offsetUs: clock.offsetUs))
    }

    private func bitrateTick() {
        guard let b = bitrate else { return }
        let nowMs = Int64(MonotonicClock.nowUs() / 1000)
        if let bps = b.tick(nowMs: nowMs) { source?.setBitrate(bps) }
        guard let p = locked({ plan }) else { return }
        if paused || linkState != .connected {
            quality.clearTimers()
            return
        }
        guard let step = quality.tick(nowMs: nowMs, bitrateBps: b.appliedBps, minBps: p.minBps, maxBps: p.maxBps) else { return }
        // Restarting the encoder takes a while; the bitrate tick must keep its pace.
        queue.async { [weak self] in self?.applyQualityStep(step) }
    }

    private func applyQualityStep(_ step: QualityStep) {
        guard !isEnded, qualityStep?(step) == true else {
            quality.onRejected()
            return
        }
        quality.onSwitched(nowMs: Int64(MonotonicClock.nowUs() / 1000), step: step)
        qualityStepsDown += step == .down ? 1 : -1
    }

    private func statsTick() {
        guard let p = locked({ plan }) else { return }
        let now = MonotonicClock.nowUs()
        let frames = locked { framesSent }
        let fps = Double(frames - statsFramesAtLastTick) * 1e6 / Double(max(1, now - statsLastTickUs))
        statsFramesAtLastTick = frames
        statsLastTickUs = now
        let applied = bitrate?.appliedBps ?? p.startBps
        let dropped = sendQueue.droppedTotal
        controlSend(.senderStats(SenderStats(bitrateBps: UInt32(clamping: applied), fpsX100: UInt16(clamping: Int(fps * 100)),
                                             framesDropped: UInt32(clamping: dropped),
                                             encoderName: String(p.encoderName.prefix(64)))))
        let latency = lastReport.flatMap { $0.c2dAvgUs > 0 ? Int($0.c2dAvgUs / 1000) : nil }
        onStats?(MirrorStats(latencyMs: latency, fps: fps, bitrateBps: applied, droppedFrames: dropped, width: p.width,
                             height: p.height, autoLowered: qualityStepsDown > 0))
    }

    private func end(_ reason: SessionEnd) {
        let first: Bool = locked {
            if ended { return false }
            ended = true
            return true
        }
        guard first else { return }
        var bye: UInt8?
        switch reason {
        case .local(let r): bye = r
        case .encoderFailed: bye = ByeReason.error
        default: break
        }
        let s = locked { session }
        let wasConnected = locked { link } == .connected
        if let bye, wasConnected { s.control.send(.bye(reason: bye)) }
        timers.forEach { $0.cancel() }
        timers = []
        sendQueue.close()
        audioQueue.close()
        source?.stop()
        audioSource?.stop()
        locked {
            video?.close()
            audio?.close()
            video = nil
            audio = nil
        }
        s.control.close()
        onEnd?(reason)
    }
}
