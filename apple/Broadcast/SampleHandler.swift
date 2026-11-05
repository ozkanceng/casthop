import CastHopProtocol
import CastHopSender
import CoreMedia
import ImageIO
import ReplayKit
import UIKit

/// Thin ReplayKit shell around `BroadcastEngine`. Extensions get about 50 MB: frames pass by reference from here to the
/// single encoder, and every queue behind it is bounded.
final class SampleHandler: RPBroadcastSampleHandler {
    private static let maxClockDriftUs: Int64 = 1_000_000
    private static let farewellSeconds = 0.5

    private var engine: BroadcastEngine?
    private var observations: [DarwinNotifier.Observation] = []
    private let finished = DispatchSemaphore(value: 0)

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard let store = SharedStore.shared() else {
            finish(.internalError)
            return
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let engine = BroadcastEngine(store: store, device: .init(name: UIDevice.current.name, appVersion: version))
        engine.onFinished = { [weak self] failure in
            self?.finished.signal()
            self?.finish(failure)
        }
        self.engine = engine
        observations = [
            DarwinNotifier.observe(.stop) { [weak engine] in engine?.stop() },
            DarwinNotifier.observe(.pause) { [weak engine] in engine?.pause() },
            DarwinNotifier.observe(.unpause) { [weak engine] in engine?.unpause() },
        ]
        engine.start()
    }

    override func broadcastPaused() { engine?.pause() }
    override func broadcastResumed() { engine?.unpause() }

    /// The teacher stopped from the status bar. iOS ends the process soon after, so the goodbye gets a moment to leave.
    override func broadcastFinished() {
        guard let engine else { return }
        engine.onFinished = { [weak self] _ in self?.finished.signal() }
        engine.stop()
        _ = finished.wait(timeout: .now() + Self.farewellSeconds)
    }

    private func finish(_ failure: MirrorFailure?) {
        let text = failure.map { [$0.title, $0.body].compactMap { $0 }.joined(separator: " ") } ?? String(localized: "Mirroring stopped.")
        finishBroadcastWithError(NSError(domain: "io.github.ozkanceng.casthop", code: 1,
                                         userInfo: [NSLocalizedDescriptionKey: text]))
    }

    /// Sample times are on the host clock, the base of `MonotonicClock`. If a release of iOS ever differs, now is close
    /// enough: only latency figures and lip sync depend on it.
    private func captureTimeUs(_ sample: CMSampleBuffer) -> UInt64 {
        let now = MonotonicClock.nowUs()
        let pts = CMTimeConvertScale(CMSampleBufferGetPresentationTimeStamp(sample), timescale: 1_000_000, method: .default)
        guard pts.isValid, abs(pts.value - Int64(now)) <= Self.maxClockDriftUs else { return now }
        return UInt64(pts.value)
    }

    private func rotation(of sample: CMSampleBuffer) -> FrameRotation {
        let value = CMGetAttachment(sample, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber
        return FrameRotation(value.flatMap { CGImagePropertyOrientation(rawValue: $0.uint32Value) } ?? .up)
    }

    override func processSampleBuffer(_ sample: CMSampleBuffer, with type: RPSampleBufferType) {
        switch type {
        case .video:
            guard let image = CMSampleBufferGetImageBuffer(sample) else { return }
            engine?.video(image, rotation: rotation(of: sample), ptsUs: captureTimeUs(sample))
        case .audioApp:
            engine?.audio(sample)
        case .audioMic:
            break // the microphone is never shared
        @unknown default:
            break
        }
    }
}
