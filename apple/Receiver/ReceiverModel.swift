import AVFoundation
import CastHopReceiver
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Options that a test run passes to the app, the same ones as `casthop-receiver`.
struct LaunchOptions {
    var port: UInt16?
    var name: String?
    var dataDirectory: URL?
    var pairingFile: URL?
    var statsFile: URL?
    var qrHosts: [String] = []
    var approvalTimeoutMs: Int64?

    init(arguments: [String]) {
        var args = Array(arguments.dropFirst())
        func value() -> String? { args.isEmpty ? nil : args.removeFirst() }
        while !args.isEmpty {
            switch args.removeFirst() {
            case "--port": port = value().flatMap { UInt16($0) }
            case "--name": name = value()
            case "--data-dir": dataDirectory = value().map { URL(fileURLWithPath: $0) }
            case "--pairing-file": pairingFile = value().map { URL(fileURLWithPath: $0) }
            case "--stats-file": statsFile = value().map { URL(fileURLWithPath: $0) }
            case "--qr-host": value().map { qrHosts.append($0) }
            case "--approval-timeout": approvalTimeoutMs = value().flatMap { Int64($0) }
            default: break
            }
        }
    }
}

/// Decoded frames go straight to the layer. Called on the decoder's thread.
final class DisplayOutput: VideoOutput, @unchecked Sendable {
    let layer = AVSampleBufferDisplayLayer()
    // Main thread only.
    private var rotated = false
    private var container = CGRect.zero

    func setRotated(_ rotated: Bool) {
        DispatchQueue.main.async { [self] in
            guard rotated != self.rotated else { return }
            self.rotated = rotated
            place(in: container)
        }
    }

    /// Lays the layer out over [bounds]. A turned picture (PROTOCOL.md §11, ROTATED) gets the turned size and is turned
    /// back counter-clockwise, so `videoGravity` keeps its aspect ratio. Main thread.
    func place(in bounds: CGRect) {
        container = bounds
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.setAffineTransform(.identity)
        let size = rotated ? CGSize(width: bounds.height, height: bounds.width) : bounds.size
        layer.bounds = CGRect(origin: .zero, size: size)
        layer.position = CGPoint(x: bounds.midX, y: bounds.midY)
#if os(macOS)
        let counterClockwise = CGFloat.pi / 2 // y points up
#else
        let counterClockwise = -CGFloat.pi / 2
#endif
        layer.setAffineTransform(rotated ? CGAffineTransform(rotationAngle: counterClockwise) : .identity)
        CATransaction.commit()
    }

    func enqueue(_ sampleBuffer: CMSampleBuffer) {
        let renderer = layer.sampleBufferRenderer
        if renderer.status == .failed { renderer.flush() }
        renderer.enqueue(sampleBuffer)
    }

    func flush() {
        layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }
}

enum PictureMode: String {
    case fit
    case fill
}

/// Screen state on top of `ReceiverService`.
@MainActor
final class ReceiverModel: ObservableObject {
    private enum Key {
        static let name = "receiverName"
        static let audio = "audioEnabled"
        static let picture = "pictureMode"
        static let dark = "darkTheme"
    }

    @Published private(set) var snapshot = ReceiverSnapshot()
    @Published var pictureMode: PictureMode {
        didSet { defaults.set(pictureMode.rawValue, forKey: Key.picture) }
    }
    @Published var darkTheme: Bool {
        didSet { defaults.set(darkTheme, forKey: Key.dark) }
    }
    @Published var showingSettings = false

    let display = DisplayOutput()
    private let launch = LaunchOptions(arguments: ProcessInfo.processInfo.arguments)
    private let defaults = UserDefaults.standard
    private var service: ReceiverService?
#if os(macOS)
    private var awake: NSObjectProtocol?
#endif

    var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }

    init() {
        pictureMode = PictureMode(rawValue: defaults.string(forKey: Key.picture) ?? "") ?? .fit
        darkTheme = defaults.object(forKey: Key.dark) as? Bool ?? true
        start()
    }

    private static var deviceName: String {
#if os(macOS)
        Host.current().localizedName ?? "CastHop"
#else
        UIDevice.current.name
#endif
    }

    private static var dataDirectory: URL? {
#if os(macOS)
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("CastHop", isDirectory: true)
#else
        nil // the keychain: tvOS keeps no files for an app
#endif
    }

    /// The screen, in pixels, and its refresh rate. A sender plans its stream from this.
    private static var screen: (width: Int, height: Int, refresh: Int) {
#if os(macOS)
        guard let s = NSScreen.main else { return (1920, 1080, 60) }
        let size = s.convertRectToBacking(s.frame).size
        return (Int(size.width), Int(size.height), s.maximumFramesPerSecond)
#else
        let s = UIScreen.main
        let size = s.nativeBounds.size
        return (Int(max(size.width, size.height)), Int(min(size.width, size.height)), s.maximumFramesPerSecond)
#endif
    }

    private func start() {
        var options = ReceiverOptions()
        options.name = launch.name ?? defaults.string(forKey: Key.name) ?? Self.deviceName
        options.port = launch.port ?? options.port
        options.dataDirectory = launch.dataDirectory ?? Self.dataDirectory
        options.pairingFile = launch.pairingFile
        options.statsFile = launch.statsFile
        options.qrHosts = launch.qrHosts
        options.approvalTimeoutMs = launch.approvalTimeoutMs ?? options.approvalTimeoutMs
        options.audio = defaults.object(forKey: Key.audio) as? Bool ?? true
        let screen = Self.screen
        options.displayWidth = screen.width
        options.displayHeight = screen.height
        options.displayRefresh = screen.refresh

        let service = ReceiverService(options: options)
        service.video.output = display
        service.onChange = { [weak self] snapshot in
            Task { @MainActor in self?.snapshot = snapshot }
        }
        self.service = service
        service.start()
        keepAwake()
    }

    private func keepAwake() {
#if os(macOS)
        awake = ProcessInfo.processInfo.beginActivity(options: [.idleDisplaySleepDisabled, .userInitiated],
                                                      reason: "Waiting for a screen to mirror")
#else
        UIApplication.shared.isIdleTimerDisabled = true
#endif
    }

    /// iOS drops listeners in the background. The receiver comes back when the app does.
    func becameActive() {
        guard snapshot.failure == .listener else { return }
        service?.shutdown()
        snapshot = ReceiverSnapshot()
        start()
    }

    func shutdown() { service?.shutdown() }
    func approve() { service?.approvePending() }
    func deny() { service?.denyPending() }
    func stopSession() { service?.stopSession() }
    func forget(_ device: TrustedDevice) { service?.forgetSender(keyId: device.id) }

    func rename(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        defaults.set(trimmed, forKey: Key.name)
        service?.setReceiverName(trimmed)
    }

    func setAudio(_ on: Bool) {
        defaults.set(on, forKey: Key.audio)
        service?.setAudioEnabled(on)
    }
}
