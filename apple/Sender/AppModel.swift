import AVFoundation
import CastHopProtocol
import CastHopSender
import Network
import SwiftUI
import UIKit

enum Route: Hashable {
    case guide
    case permissions
    case scanner
    case settings
    case discover
    case pin(String)
}

/// What the home screen shows.
enum AppPhase: Equatable {
    case idle
    /// The app is pairing with `name`.
    case pairing(name: String)
    case pairingApproval(name: String)
    /// Paired. Only the system prompt is left, and only the teacher can answer it.
    case ready(RememberedReceiver)
    /// The extension is running.
    case broadcast(BroadcastStatus)
    /// `failure` is nil after a stop that was asked for.
    case ended(failure: MirrorFailure?)
}

@MainActor
final class AppModel: ObservableObject {
    @Published var path: [Route] = []
    @Published private(set) var phase = AppPhase.idle
    @Published private(set) var receivers: [RememberedReceiver] = []
    @Published private(set) var discovered: [DiscoveredReceiver] = []
    @Published var settings: SenderSettings {
        didSet { store?.settings = settings }
    }

    private let store: SharedStore?
    private var browser: ReceiverBrowser?
    private var pairing: Task<Void, Never>?
    private var statusObservation: DarwinNotifier.Observation?
    private var returnToScanner = false

    var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }
    var storeAvailable: Bool { store != nil }

    init() {
        let store = SharedStore.shared()
        self.store = store
        settings = store?.settings ?? SenderSettings()
        reload()
        statusObservation = DarwinNotifier.observe(.status) { [weak self] in
            Task { @MainActor in self?.statusChanged() }
        }
        statusChanged()
    }

    private func reload() { receivers = store?.receivers() ?? [] }

    private var device: DeviceInfo? {
        guard let store else { return nil }
        let caps = VideoToolboxCaps.available(allowH265: settings.hevcEnabled)
        return DeviceInfo(name: UIDevice.current.name, deviceId: store.deviceId, appVersion: version, videoCodecs: caps.map(\.codec),
                          audioCodecs: [Codec.aacLc], maxWidth: 3840, maxHeight: 2160, maxFps: 60)
    }

    // MARK: Permissions

    var cameraAllowed: Bool { AVCaptureDevice.authorizationStatus(for: .video) == .authorized }
    var cameraDenied: Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        return status == .denied || status == .restricted
    }

    func openScanner() {
        path.append(cameraAllowed ? .scanner : .permissions)
    }

    /// iOS has no call that asks for local network access: the first browse brings up its prompt.
    func requestPermissions() async {
        startBrowsing()
        _ = await AVCaptureDevice.requestAccess(for: .video)
        objectWillChange.send()
        if cameraAllowed, path.last == .permissions {
            path[path.count - 1] = .scanner
        }
    }

    // MARK: Discovery

    func startBrowsing() {
        guard browser == nil else { return }
        let browser = ReceiverBrowser()
        browser.onChange = { [weak self] found in
            Task { @MainActor in self?.discovered = found }
        }
        browser.start()
        self.browser = browser
    }

    func stopBrowsing() {
        browser?.stop()
        browser = nil
        discovered = []
    }

    func discovered(id: String) -> DiscoveredReceiver? { discovered.first { $0.id == id } }

    // MARK: Connecting

    func connect(qr: QrPayload) {
        path = []
        pair(.qr(qr), name: qr.name ?? qr.hosts.first ?? "")
    }

    func connect(_ found: DiscoveredReceiver, pin: String) {
        path = []
        stopBrowsing()
        pair(.discovered(found, pin: pin), name: found.name)
    }

    func connect(_ receiver: RememberedReceiver) {
        guard let store else { return }
        store.selectedFingerprint = receiver.fingerprint
        store.touch(fingerprint: receiver.fingerprint, nowMs: Self.nowMs)
        reload()
        phase = .ready(receiver)
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func pair(_ target: ConnectTarget, name: String) {
        guard let store, let device else {
            phase = .ended(failure: .internalError)
            return
        }
        phase = .pairing(name: name)
        pairing = Task { [weak self] in
            let result = await ConnectFlow.pair(target: target, device: device, store: store, nowMs: Self.nowMs) { _ in
                Task { @MainActor in self?.phase = .pairingApproval(name: name) }
            }
            guard let self, !Task.isCancelled else { return }
            switch result {
            case .success(let receiver):
                store.selectedFingerprint = receiver.fingerprint
                self.reload()
                self.phase = .ready(receiver)
            case .failure(let failure):
                self.reload()
                self.phase = .ended(failure: failure)
            }
        }
    }

    func forget(_ receiver: RememberedReceiver) {
        store?.remove(fingerprint: receiver.fingerprint)
        reload()
    }

    func dismiss() {
        pairing?.cancel()
        phase = .idle
    }

    // MARK: Broadcast

    func stop() { DarwinNotifier.post(.stop) }
    func pause() { DarwinNotifier.post(.pause) }
    func unpause() { DarwinNotifier.post(.unpause) }

    private func statusChanged() {
        guard let status = store?.status else { return }
        switch status.phase {
        case .idle:
            break
        case .connecting, .waitingApproval, .starting, .mirroring:
            phase = .broadcast(status)
        case .ended:
            phase = .ended(failure: status.failure)
            store?.status = BroadcastStatus()
            reload()
        }
    }

    /// The system can end the extension without a word. A screen that is no longer captured means it is gone.
    func becameActive() {
        reload()
        guard let store else { return }
        let running: [BroadcastPhase] = [.connecting, .waitingApproval, .starting, .mirroring]
        if running.contains(store.status.phase), !UIScreen.main.isCaptured {
            store.status = BroadcastStatus()
            phase = .ended(failure: .broadcastEnded)
        } else {
            statusChanged()
        }
    }

    // MARK: Settings

    /// What this device can encode, for the advanced settings. Unsupported values are not offered.
    var encoderOptions: (longSides: [Int], fps: [Int], hevc: Bool) {
        let bounds = UIScreen.main.nativeBounds
        let width = Int(bounds.width)
        let height = Int(bounds.height)
        let encoders = VideoToolboxCaps.available(allowH265: false)
        let hevc = VideoToolboxCaps.available(allowH265: true).contains { $0.codec == Codec.h265 }
        return (StreamPlanner.supportedLongSides(encoders: encoders, sourceWidth: width, sourceHeight: height),
                StreamPlanner.supportedFps(encoders: encoders, sourceWidth: width, sourceHeight: height), hevc)
    }

    static let bitrateOptions: [Int64] = [4, 8, 12, 16, 24].map { $0 * 1_000_000 }
}
