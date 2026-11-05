import AVFoundation
import CastHopProtocol
import CastHopSender
import CastHopTransport
import CoreMedia
import Foundation

// Streams a media file through the same session and VideoToolbox path as the Broadcast Upload Extension.
//
// casthop-sender --pairing-file F --input movie.mp4 [--auth qr|pin] [--host H] [--port N] [--preset best|balanced|weak]
//                [--frames N] [--rotate cw90|ccw90|180] [--no-audio] [--device-name NAME] [--broadcast-store DIR]
//
// --broadcast-store takes the path of the iOS app and extension: pair first, leave, then broadcast with the pairing key
// through BroadcastEngine, with DIR as the App Group container.

struct Options {
    var pairingFile: String?
    var input: String?
    var auth = "qr"
    var host: String?
    var port: UInt16?
    var preset = Preset.balanced
    var frames = Int.max
    var rotation = FrameRotation.none
    var audio = true
    var deviceName = "casthop-sender"
    var broadcastStore: String?
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("casthop-sender: \(message)\n".utf8))
    exit(1)
}

func parse() -> Options {
    var o = Options()
    var args = Array(CommandLine.arguments.dropFirst())
    func value() -> String {
        guard !args.isEmpty else { fail("missing value") }
        return args.removeFirst()
    }
    while !args.isEmpty {
        switch args.removeFirst() {
        case "--pairing-file": o.pairingFile = value()
        case "--input": o.input = value()
        case "--auth": o.auth = value()
        case "--host": o.host = value()
        case "--port": o.port = UInt16(value())
        case "--frames": o.frames = Int(value()) ?? .max
        case "--no-audio": o.audio = false
        case "--device-name": o.deviceName = value()
        case "--broadcast-store": o.broadcastStore = value()
        case "--preset":
            switch value() {
            case "best": o.preset = .best
            case "balanced": o.preset = .balanced
            case "weak": o.preset = .weakNetwork
            default: fail("unknown preset")
            }
        case "--rotate":
            switch value() {
            case "cw90": o.rotation = .clockwise90
            case "ccw90": o.rotation = .counterClockwise90
            case "180": o.rotation = .half
            default: fail("unknown rotation")
            }
        default: fail("unknown option")
        }
    }
    return o
}

let options = parse()
guard let pairingPath = options.pairingFile, let inputPath = options.input else { fail("--pairing-file and --input are required") }
guard let pairingData = FileManager.default.contents(atPath: pairingPath),
      let pairing = try? JSONSerialization.jsonObject(with: pairingData) as? [String: Any],
      let uri = pairing["uri"] as? String, let qr = try? QrPayload.parse(uri) else { fail("pairing file is not readable") }

/// Reads the file in real time and feeds the capture path.
final class FileCapture: @unchecked Sendable {
    private let asset: AVURLAsset
    private let reader: AVAssetReader
    private let videoOutput: AVAssetReaderTrackOutput
    private let audioOutput: AVAssetReaderTrackOutput?
    private var next: () -> CMSampleBuffer? = { nil }

    init(path: String, audio: Bool) async throws {
        asset = AVURLAsset(url: URL(fileURLWithPath: path))
        reader = try AVAssetReader(asset: asset)
        guard let video = try await asset.loadTracks(withMediaType: .video).first else { fail("no video track") }
        videoOutput = AVAssetReaderTrackOutput(track: video, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ])
        videoOutput.alwaysCopiesSampleData = false
        reader.add(videoOutput)
        if audio, let track = try await asset.loadTracks(withMediaType: .audio).first {
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
            ])
            reader.add(output)
            audioOutput = output
        } else {
            audioOutput = nil
        }
        guard reader.startReading() else { fail("cannot read the file") }
    }

    var hasAudio: Bool { audioOutput != nil }

    /// Decoding a file stalls now and then on a busy machine. Screen capture does not, so frames are read ahead and a
    /// late frame moves the clock instead of a burst following it.
    private func prefetch(limit: Int) -> (() -> CMSampleBuffer?) {
        let condition = NSCondition()
        var queue: [CMSampleBuffer] = []
        var ended = false
        let capacity = 48
        Thread.detachNewThread { [videoOutput] in
            var read = 0
            while read < limit, let sample = videoOutput.copyNextSampleBuffer() {
                read += 1
                condition.lock()
                while queue.count >= capacity { condition.wait() }
                queue.append(sample)
                condition.broadcast()
                condition.unlock()
            }
            condition.lock()
            ended = true
            condition.broadcast()
            condition.unlock()
        }
        condition.lock()
        while queue.count < capacity && !ended { condition.wait() }
        condition.unlock()
        return {
            condition.lock()
            defer { condition.unlock() }
            while queue.isEmpty && !ended { condition.wait() }
            guard !queue.isEmpty else { return nil }
            condition.broadcast()
            return queue.removeFirst()
        }
    }

    /// Fills the read-ahead before the session starts, so that its first frame is not late.
    func prepare(frames: Int) { next = prefetch(limit: frames) }

    func run(rotation: FrameRotation, video: (CVPixelBuffer, UInt64) -> Void, audio: (CMSampleBuffer) -> Void,
             firstFrame: (Int, Int) -> Void) {
        var startHost: UInt64?
        var startPts = 0.0
        var firstSeen = false
        var pendingAudio: CMSampleBuffer?
        while let sample = next(), let image = CMSampleBufferGetImageBuffer(sample) {
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if !firstSeen {
                firstSeen = true
                let size = rotation.displaySize(width: CVPixelBufferGetWidth(image), height: CVPixelBufferGetHeight(image))
                firstFrame(size.0, size.1)
                startHost = MonotonicClock.nowUs() // setting up took a while: the clock starts now
                startPts = pts
            }
            var dueUs = startHost! + UInt64(max(pts - startPts, 0) * 1e6)
            let now = MonotonicClock.nowUs()
            if dueUs > now {
                usleep(UInt32(min(dueUs - now, 1_000_000)))
            } else if now - dueUs > 50_000 {
                startHost! += now - dueUs
                dueUs = now
            }
            video(image, MonotonicClock.nowUs())
            while let output = audioOutput {
                guard let nextAudio = pendingAudio ?? output.copyNextSampleBuffer() else { break }
                let audioPts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(nextAudio))
                if audioPts > pts {
                    pendingAudio = nextAudio
                    break
                }
                pendingAudio = nil
                push(nextAudio, to: audio, startHost: startHost!, startPts: startPts)
            }
        }
        reader.cancelReading()
    }

    private func push(_ sample: CMSampleBuffer, to audio: (CMSampleBuffer) -> Void, startHost: UInt64, startPts: Double) {
        let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
        var timing = CMSampleTimingInfo(duration: .invalid,
                                        presentationTimeStamp: CMTime(value: Int64(startHost) + Int64((pts - startPts) * 1e6), timescale: 1_000_000),
                                        decodeTimeStamp: .invalid)
        var retimed: CMSampleBuffer?
        CMSampleBufferCreateCopyWithNewTiming(allocator: nil, sampleBuffer: sample, sampleTimingEntryCount: 1,
                                              sampleTimingArray: &timing, sampleBufferOut: &retimed)
        if let retimed { audio(retimed) }
    }
}

let endpoint = ReceiverEndpoint(host: options.host ?? qr.hosts[0], port: options.port ?? qr.port, fingerprint: qr.certFingerprint)
let credentials: Credentials
switch options.auth {
case "qr": credentials = .qrToken(qr.token)
case "pin":
    guard let pin = pairing["pin"] as? String else { fail("no PIN in the pairing file") }
    credentials = .pin(pin)
default: fail("unknown auth method")
}

let caps = VideoToolboxCaps.available(allowH265: false)
let device = DeviceInfo(name: options.deviceName, deviceId: Auth.randomBytes(ControlCodec.idSize), appVersion: "1.0",
                        videoCodecs: caps.map(\.codec), audioCodecs: [Codec.aacLc], maxWidth: 3840, maxHeight: 2160, maxFps: 60)

/// What the app and the extension do on a device, with a file in place of the screen.
func broadcast(directory: String) async throws {
    let store = SharedStore(directory: URL(fileURLWithPath: directory))
    let capture = try await FileCapture(path: inputPath, audio: options.audio)
    capture.prepare(frames: options.frames)
    var settings = store.settings
    settings.preset = options.preset
    settings.audioEnabled = options.audio
    store.settings = settings

    if store.selectedReceiver == nil {
        let info = DeviceInfo(name: options.deviceName, deviceId: store.deviceId, appVersion: "1.0", videoCodecs: caps.map(\.codec),
                              audioCodecs: [Codec.aacLc], maxWidth: 3840, maxHeight: 2160, maxFps: 60)
        switch await ConnectFlow.pair(target: .qr(qr), device: info, store: store, nowMs: Int64(Date().timeIntervalSince1970 * 1000)) {
        case .success(let receiver):
            store.selectedFingerprint = receiver.fingerprint
            print("paired with \(receiver.name)")
        case .failure(let failure):
            fail("pairing failed: \(failure)")
        }
    }

    let engine = BroadcastEngine(store: store, device: .init(name: options.deviceName, appVersion: "1.0"), sendsAudio: options.audio)
    let done = DispatchSemaphore(value: 0)
    engine.onFinished = { failure in
        print("broadcast ended: \(failure.map { "\($0)" } ?? "stopped")")
        done.signal()
    }
    engine.start()
    Thread.detachNewThread {
        capture.run(rotation: options.rotation, video: { engine.video($0, rotation: options.rotation, ptsUs: $1) },
                    audio: { engine.audio($0) }, firstFrame: { _, _ in })
        Thread.sleep(forTimeInterval: 0.5)
        print("status: \(store.status.phase), audio \(store.status.audio)")
        engine.stop()
    }
    await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
        DispatchQueue.global().async {
            done.wait()
            c.resume()
        }
    }
}

Task {
    do {
        if let directory = options.broadcastStore {
            try await broadcast(directory: directory)
            exit(0)
        }
        let capture = try await FileCapture(path: inputPath, audio: options.audio)
        capture.prepare(frames: options.frames)
        let outcome = try await SenderClient.hello(endpoint: endpoint, credentials: credentials, device: device)
        guard case .accepted(let session) = outcome else { fail("receiver refused: \(outcome)") }
        let mirror = MirrorSession(session: session)
        let done = DispatchSemaphore(value: 0)
        mirror.onEnd = { reason in
            print("session ended: \(reason)")
            done.signal()
        }
        mirror.onStats = { s in
            print(String(format: "fps %.1f bitrate %.1f Mbps dropped %lld latency %@ ms", s.fps, Double(s.bitrateBps) / 1e6,
                         s.droppedFrames, s.latencyMs.map(String.init) ?? "-"))
        }
        mirror.start()

        let encoder = VideoToolboxEncoder()
        let pipeline = ScreenPipeline(encoder: encoder)
        let w = session.welcome
        let display = ReceiverDisplay(width: Int(w.displayWidth), height: Int(w.displayHeight), refreshHz: Int(w.displayRefresh))
        func plan(_ width: Int, _ height: Int) -> StreamPlan {
            guard let p = StreamPlanner.plan(sourceWidth: width, sourceHeight: height, preset: options.preset, receiver: display,
                                             encoders: caps) else { fail("no usable encoder") }
            return p
        }
        let audio = capture.hasAudio && w.audioEnabled ? AacEncoder() : nil
        let started = DispatchSemaphore(value: 0)
        Thread.detachNewThread {
            capture.run(rotation: options.rotation, video: { pipeline.push($0, rotation: options.rotation, ptsUs: $1) },
                        audio: { audio?.push($0) }) { width, height in
                let p = plan(width, height)
                pipeline.applied(width: p.width, height: p.height)
                Task {
                    do {
                        try await mirror.startVideo(plan: p, source: encoder)
                        print("streaming \(p.width)x\(p.height)@\(p.fps) \(p.encoderName), hardware \(p.hardware)")
                        if let audio { try await mirror.startAudio(audio) }
                    } catch {
                        fail("cannot start: \(error)")
                    }
                    started.signal()
                }
                started.wait()
            }
            // Let the last frames leave before saying goodbye.
            Thread.sleep(forTimeInterval: 0.5)
            print("encoder: submitted \(encoder.submitted), behind \(encoder.skippedBusy), over rate \(encoder.skippedRate)")
            mirror.stop(byeReason: ByeReason.userStop)
        }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async {
                done.wait()
                c.resume()
            }
        }
        exit(0)
    } catch {
        fail("\(error)")
    }
}
dispatchMain()
