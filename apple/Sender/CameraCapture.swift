import AVFoundation
import CastHopSender
import SwiftUI

/// The camera as a frame source for `BroadcastEngine`, in the app. Frames arrive upright: `AVCaptureDevice.RotationCoordinator`
/// follows the device, so turning it looks like any other rotation to the encoder.
final class CameraCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    private static let presets: [AVCaptureSession.Preset] = [.hd1920x1080, .hd1280x720, .high]

    let preview: AVCaptureVideoPreviewLayer
    let canSwitch: Bool

    /// On the capture queue. The buffer is not copied.
    var onFrame: ((CVPixelBuffer, UInt64) -> Void)?
    /// True while the system holds the camera, for instance for another app beside this one on an iPad.
    var onInterruption: ((Bool) -> Void)?
    var onFailure: (() -> Void)?

    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "casthop.camera", qos: .userInteractive)
    private var input: AVCaptureDeviceInput?
    private var observers: [NSObjectProtocol] = []

    // Main thread: the coordinator reads the preview layer.
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var rotationWatches: [NSKeyValueObservation] = []

    private static func camera(_ position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
    }

    override init() {
        preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspect
        canSwitch = Self.camera(.back) != nil && Self.camera(.front) != nil
        super.init()
    }

    /// Starts on the back camera, the one that can point at a page on the desk.
    func start() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: .AVCaptureSessionWasInterrupted, object: session, queue: nil) { [weak self] _ in
                self?.onInterruption?(true)
            },
            center.addObserver(forName: .AVCaptureSessionInterruptionEnded, object: session, queue: nil) { [weak self] _ in
                self?.onInterruption?(false)
            },
            center.addObserver(forName: .AVCaptureSessionRuntimeError, object: session, queue: nil) { [weak self] _ in
                self?.onFailure?()
            },
        ]
        queue.async { [self] in
            guard let device = Self.camera(.back) ?? Self.camera(.front), configure(device) else {
                onFailure?()
                return
            }
            session.startRunning()
        }
    }

    func stop() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        rotationWatches = []
        rotation = nil
        queue.async { [self] in
            output.setSampleBufferDelegate(nil, queue: nil)
            session.stopRunning()
        }
    }

    func switchPosition() {
        queue.async { [self] in
            guard let current = input,
                  let next = Self.camera(current.device.position == .back ? .front : .back),
                  let nextInput = try? AVCaptureDeviceInput(device: next) else { return }
            session.beginConfiguration()
            session.removeInput(current)
            if session.canAddInput(nextInput) {
                session.addInput(nextInput)
                input = nextInput
            } else {
                session.addInput(current)
            }
            session.commitConfiguration()
            followRotation(of: input?.device ?? next)
        }
    }

    private func configure(_ device: AVCaptureDevice) -> Bool {
        guard let deviceInput = try? AVCaptureDeviceInput(device: device) else { return false }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        guard session.canAddInput(deviceInput), session.canAddOutput(output) else { return false }
        session.addInput(deviceInput)
        input = deviceInput
        if let preset = Self.presets.first(where: session.canSetSessionPreset) {
            session.sessionPreset = preset
        }
        // The format ReplayKit delivers, so the encoder sees one kind of frame.
        let format = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        if output.availableVideoPixelFormatTypes.contains(format) {
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: format]
        }
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        session.addOutput(output)
        followRotation(of: device)
        return true
    }

    private func followRotation(of device: AVCaptureDevice) {
        DispatchQueue.main.async { [self] in
            let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: preview)
            rotation = coordinator
            rotationWatches = [
                coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) { [weak self] c, _ in
                    let angle = c.videoRotationAngleForHorizonLevelPreview
                    DispatchQueue.main.async { self?.rotatePreview(angle) }
                },
                coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.initial, .new]) { [weak self] c, _ in
                    let angle = c.videoRotationAngleForHorizonLevelCapture
                    self?.queue.async { self?.rotateOutput(angle) }
                },
            ]
        }
    }

    private func rotatePreview(_ angle: CGFloat) {
        if let connection = preview.connection, connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }

    private func rotateOutput(_ angle: CGFloat) {
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let image = CMSampleBufferGetImageBuffer(sample) else { return }
        onFrame?(image, BroadcastEngine.captureTimeUs(sample))
    }
}

/// What the camera sees, for aiming it. The picture on the board comes from the encoder, not from here.
struct CameraPreview: UIViewRepresentable {
    let layer: AVCaptureVideoPreviewLayer

    final class Host: UIView {
        var previewLayer: CALayer? {
            didSet {
                oldValue?.removeFromSuperlayer()
                previewLayer.map(self.layer.addSublayer)
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            previewLayer?.frame = bounds
        }
    }

    func makeUIView(context: Context) -> Host {
        let host = Host()
        host.backgroundColor = .black
        host.previewLayer = layer
        return host
    }

    func updateUIView(_ host: Host, context: Context) {
        if host.previewLayer !== layer { host.previewLayer = layer }
    }
}
