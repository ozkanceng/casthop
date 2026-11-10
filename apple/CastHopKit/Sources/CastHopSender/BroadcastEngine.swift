import CastHopProtocol
import CoreMedia
import CoreVideo
import Foundation

/// One mirroring session without ReplayKit: connects to the selected receiver with its pairing key, plans the stream
/// from the first frame, follows rotation, reports status to the app. A thin `RPBroadcastSampleHandler` calls it, the app
/// calls it for the camera, and `casthop-sender --broadcast-store` drives it on a Mac.
public final class BroadcastEngine: @unchecked Sendable {
    private static let maxClockDriftUs: Int64 = 1_000_000

    public struct Device: Sendable {
        public var name: String
        public var appVersion: String

        public init(name: String, appVersion: String) {
            self.name = name
            self.appVersion = appVersion
        }
    }

    /// Runs once. Nil after a stop that was asked for.
    public var onFinished: ((MirrorFailure?) -> Void)?

    private let store: SharedStore
    private let device: Device
    private let sendsAudio: Bool
    private let settings: SenderSettings
    private let encoder = VideoToolboxEncoder()
    private let pipeline: ScreenPipeline
    private let queue = DispatchQueue(label: "casthop.broadcast", qos: .userInteractive)
    private let lock = NSLock()

    // Guarded by `lock`: the capture threads read these.
    private var videoRunning = false
    private var paused = false
    private var audio: AacEncoder?
    private var firstFrameSize: (Int, Int)?

    // Owned by `queue`.
    private var mirror: MirrorSession?
    private var preset: Preset
    /// The receiver of this session; keys the remembered preset.
    private var fingerprint: Data?
    private var plan: StreamPlan?
    private var videoStarting = false
    private var finished = false
    private var status = BroadcastStatus()
    private var stopRequested: UInt8?
    private var stopFailure: MirrorFailure?

    /// `sendsAudio` is false for a source with no sound of its own.
    public init(store: SharedStore, device: Device, sendsAudio: Bool = true) {
        self.store = store
        self.device = device
        self.sendsAudio = sendsAudio
        settings = store.settings
        preset = settings.preset
        pipeline = ScreenPipeline(encoder: encoder)
        pipeline.onSourceChanged = { [weak self] width, height in
            self?.queue.async { self?.sourceChanged(width, height) }
        }
    }

    private func encoders(for welcome: Welcome) -> [VideoToolboxCaps] {
        // H.265 only if asked for and the receiver has hardware for it.
        let receiverHevc = welcome.videoDecoders.contains {
            $0.codec == Codec.h265 && $0.flags & CodecSupport.flagHardware != 0
        }
        let decodable = Set(welcome.videoDecoders.map(\.codec))
        return VideoToolboxCaps.available(allowH265: settings.hevcEnabled && receiverHevc).filter { decodable.contains($0.codec) }
    }

    private func publish(_ change: (inout BroadcastStatus) -> Void) {
        change(&status)
        store.status = status
    }

    public func start() {
        queue.async { [self] in
            guard let target = store.selectedReceiver else {
                finish(.noTarget)
                return
            }
            fingerprint = target.fingerprint
            publish {
                $0 = BroadcastStatus()
                $0.phase = .connecting
                $0.receiverName = target.name
            }
            let caps = VideoToolboxCaps.available(allowH265: settings.hevcEnabled)
            let info = DeviceInfo(name: device.name, deviceId: store.deviceId, appVersion: device.appVersion,
                                  videoCodecs: caps.map(\.codec), audioCodecs: [Codec.aacLc], maxWidth: 3840, maxHeight: 2160,
                                  maxFps: 60)
            Task {
                let result = await ConnectFlow.open(target: .remembered(target), device: info, requestPairing: false, store: self.store) { [weak self] _ in
                    self?.queue.async { self?.publish { $0.phase = .waitingApproval } }
                }
                self.queue.async { self.connected(result) }
            }
        }
    }

    private func connected(_ result: Result<Session, MirrorFailure>) {
        let session: Session
        switch result {
        case .failure(let failure):
            finish(failure)
            return
        case .success(let s):
            session = s
        }
        if let reason = stopRequested {
            session.control.send(.bye(reason: reason))
            session.control.close()
            finish(nil)
            return
        }
        let mirror = MirrorSession(session: session)
        mirror.onEnd = { [weak self] end in self?.queue.async { self?.finish(MirrorFailure.from(end: end)) } }
        mirror.onStats = { [weak self] stats in
            self?.queue.async { self?.publish { $0.stats = BroadcastStats(stats) } }
        }
        mirror.onLink = { [weak self] link in
            self?.queue.async { self?.publish { $0.reconnecting = link == .reconnecting } }
        }
        mirror.onAudioIssue = { [weak self] _ in
            self?.queue.async { self?.publish { $0.audio = .failed } }
        }
        mirror.fallbackPlan = { [weak self] current in self?.queue.sync { self?.step(.down, avoiding: current) } }
        mirror.qualityStep = { [weak self] step in
            guard let self else { return false }
            return self.queue.sync {
                // Advanced settings are the teacher's own choice.
                guard self.settings.overrides == StreamOverrides(), let next = self.step(step, avoiding: self.plan) else { return false }
                self.mirror?.reconfigure(next)
                if let fingerprint = self.fingerprint {
                    self.store.savePreset(self.preset == self.settings.preset ? nil : self.preset, fingerprint: fingerprint)
                }
                return true
            }
        }
        self.mirror = mirror
        // Advanced settings fix the stream; otherwise start where the network last settled with this receiver.
        if settings.overrides == StreamOverrides(), let fingerprint {
            preset = Preset.start(chosen: settings.preset, remembered: store.preset(fingerprint: fingerprint))
        }
        mirror.start()
        publish {
            $0.phase = .starting
            $0.receiverName = session.welcome.receiverName
        }
        startVideoIfReady()
    }

    private func makePlan(_ width: Int, _ height: Int, _ preset: Preset) -> StreamPlan? {
        guard let w = mirror?.welcome else { return nil }
        let display = ReceiverDisplay(width: Int(w.displayWidth), height: Int(w.displayHeight), refreshHz: Int(w.displayRefresh))
        return StreamPlanner.plan(sourceWidth: width, sourceHeight: height, preset: preset, receiver: display,
                                  encoders: encoders(for: w), overrides: settings.overrides)
    }

    /// Moves one preset, never above the one the teacher chose.
    private func step(_ step: QualityStep, avoiding current: StreamPlan?) -> StreamPlan? {
        guard let size = pipeline.currentSourceSize,
              let next = step == .down ? preset.lower : preset.higher,
              step == .down || next.index >= settings.preset.index,
              let lower = makePlan(size.0, size.1, next), lower != current else { return nil }
        preset = next
        plan = lower
        pipeline.applied(width: lower.width, height: lower.height)
        return lower
    }

    private func startVideoIfReady() {
        guard let mirror, !videoStarting, let size = lock.withLock({ firstFrameSize }) else { return }
        videoStarting = true
        guard let first = makePlan(size.0, size.1, preset) else {
            mirror.stop(byeReason: ByeReason.error)
            finish(.noEncoder)
            return
        }
        plan = first
        pipeline.applied(width: first.width, height: first.height)
        let welcome = mirror.welcome
        let audioSource = chooseAudio(welcome)
        Task {
            do {
                try await mirror.startVideo(plan: first, source: self.encoder)
            } catch {
                self.queue.async {
                    mirror.stop(byeReason: ByeReason.error)
                    self.finish(.encoderFailed)
                }
                return
            }
            self.lock.withLock { self.videoRunning = true }
            self.queue.async { self.publish { $0.phase = .mirroring } }
            guard let audioSource else { return }
            do {
                try await mirror.startAudio(audioSource)
                self.lock.withLock { self.audio = audioSource }
            } catch {
                self.queue.async { self.publish { $0.audio = .failed } }
            }
        }
    }

    private func chooseAudio(_ welcome: Welcome) -> AacEncoder? {
        let state: AudioStatus
        if !sendsAudio {
            state = .offCamera
        } else if !settings.audioEnabled {
            state = .offSetting
        } else if !welcome.audioEnabled {
            state = .offReceiver
        } else if !welcome.audioDecoders.contains(where: { $0.codec == Codec.aacLc }) {
            state = .unsupported
        } else {
            state = .on
        }
        publish { $0.audio = state }
        return state == .on ? AacEncoder() : nil
    }

    private func sourceChanged(_ width: Int, _ height: Int) {
        guard let mirror, let current = plan, let next = makePlan(width, height, preset), next != current else { return }
        plan = next
        pipeline.applied(width: next.width, height: next.height)
        mirror.reconfigure(next)
    }

    /// From the capture thread. The buffer is not copied.
    public func video(_ image: CVPixelBuffer, rotation: FrameRotation, ptsUs: UInt64) {
        lock.lock()
        let running = videoRunning
        let idle = paused
        let waiting = firstFrameSize == nil
        if waiting {
            firstFrameSize = rotation.displaySize(width: CVPixelBufferGetWidth(image), height: CVPixelBufferGetHeight(image))
        }
        lock.unlock()
        if running {
            if !idle { pipeline.push(image, rotation: rotation, ptsUs: ptsUs) }
        } else if waiting {
            queue.async { [self] in startVideoIfReady() }
        }
    }

    public func audio(_ sample: CMSampleBuffer) {
        lock.lock()
        let target = paused ? nil : audio
        lock.unlock()
        target?.push(sample)
    }

    public func pause() {
        lock.withLock { paused = true }
        queue.async { [self] in
            mirror?.pause()
            publish { $0.paused = true }
        }
    }

    public func unpause() {
        lock.withLock { paused = false }
        queue.async { [self] in
            mirror?.unpause()
            publish { $0.paused = false }
        }
    }

    /// `reason` is a `ByeReason`, `failure` what the teacher is told instead of the usual result. The answer comes
    /// through `onFinished`.
    public func stop(reason: UInt8 = ByeReason.userStop, failure: MirrorFailure? = nil) {
        queue.async { [self] in
            guard !finished else { return }
            stopFailure = failure
            if let mirror {
                mirror.stop(byeReason: reason)
            } else {
                stopRequested = reason
            }
        }
    }

    /// A sample's time on the host clock, the base of `MonotonicClock`. If a release of iOS ever differs, now is close
    /// enough: only latency figures and lip sync depend on it.
    public static func captureTimeUs(_ sample: CMSampleBuffer) -> UInt64 {
        let now = MonotonicClock.nowUs()
        let pts = CMTimeConvertScale(CMSampleBufferGetPresentationTimeStamp(sample), timescale: 1_000_000, method: .default)
        guard pts.isValid, abs(pts.value - Int64(now)) <= maxClockDriftUs else { return now }
        return UInt64(pts.value)
    }

    private func finish(_ result: MirrorFailure?) {
        guard !finished else { return }
        finished = true
        let failure = stopFailure ?? result
        lock.withLock { videoRunning = false }
        mirror = nil
        publish {
            $0.phase = .ended
            $0.failure = failure
            $0.paused = false
            $0.reconnecting = false
            $0.stats = nil
        }
        onFinished?(failure)
    }
}
