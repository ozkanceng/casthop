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
    case manualIp
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

    var isConnectingFlow: Bool {
        switch self {
        case .pairing, .pairingApproval: return true
        case .broadcast(let s):
            return s.phase == .connecting || s.phase == .waitingApproval || s.phase == .starting
        default: return false
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var path: [Route] = []
    @Published private(set) var phase = AppPhase.idle
    @Published private(set) var receivers: [RememberedReceiver] = []
    @Published private(set) var discovered: [DiscoveredReceiver] = []
    @Published private(set) var discoveryFailed = false
    /// Set while the camera is mirrored. The screen goes through the extension instead.
    @Published private(set) var camera: CameraCapture?
    @Published var settings: SenderSettings {
        didSet { store?.settings = settings }
    }

    private let store: SharedStore?
    private var browser: ReceiverBrowser?
    private var pairing: Task<Void, Never>?
    private var statusObservation: DarwinNotifier.Observation?
    private var cameraEngine: BroadcastEngine?
    /// By the teacher, as opposed to an interruption that ends by itself.
    private var cameraPaused = false
    private var lastPair: (target: ConnectTarget, name: String, keepRoute: Bool)?
    private var lastRememberedFingerprint: Data?
    private var prewarmTask: Task<Void, Never>?
    private var passiveBrowser: ReceiverBrowser?
    /// Remembered receiver fingerprints currently visible on the active local network via passive Bonjour.
    @Published private(set) var onlineFingerprints: Set<Data> = []
    @Published private(set) var availableUpdate: UpdateRelease?
    @Published private(set) var checkingUpdates = false
    @Published private(set) var updateStatusMessage: String?
    @Published var autoCheckUpdates: Bool {
        didSet { UserDefaults.standard.set(autoCheckUpdates, forKey: UpdateKey.auto) }
    }
    @Published private var updateSessionDismissed = false

    private enum UpdateKey {
        static let auto = "casthop_auto_check_updates"
        static let skipped = "casthop_skipped_update_version"
        static let lastCheck = "casthop_last_update_check"
    }

    var visibleUpdate: UpdateRelease? { updateSessionDismissed ? nil : availableUpdate }

    var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }
    var storeAvailable: Bool { store != nil }
    var recentIps: [String] { store?.recentIps() ?? [] }

    init() {
        let store = SharedStore.shared()
        self.store = store
        settings = store?.settings ?? SenderSettings()
        autoCheckUpdates = UserDefaults.standard.object(forKey: UpdateKey.auto) as? Bool ?? true
        reload()
        statusObservation = DarwinNotifier.observe(.status) { [weak self] in
            Task { @MainActor in self?.statusChanged() }
        }
        statusChanged()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            await self?.checkForUpdatesIfDue()
        }
    }

    // MARK: Updates

    func checkForUpdatesIfDue() async {
        guard autoCheckUpdates, phase == .idle else { return }
        let defaults = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        let last = defaults.double(forKey: UpdateKey.lastCheck)
        if last > 0, (now - last) < UpdateClient.checkIntervalSeconds { return }
        await performUpdateCheck(manual: false)
    }

    func checkForUpdatesNow() {
        guard !checkingUpdates else { return }
        Task { await performUpdateCheck(manual: true) }
    }

    private func performUpdateCheck(manual: Bool) async {
        guard !checkingUpdates else { return }
        guard let current = SemVer.parse(version) else { return }
        checkingUpdates = true
        if manual { updateStatusMessage = String(localized: "Checking for updates…") }
        defer { checkingUpdates = false }

        let bundleId = Bundle.main.bundleIdentifier ?? "com.sorumatik.casthop"
        let region = Locale.current.region?.identifier ?? "TR"
        guard let release = await UpdateClient.fetchAppStoreRelease(bundleId: bundleId, currentVersion: version, countryCode: region) else {
            if manual { updateStatusMessage = String(localized: "Could not check for updates. Check the internet connection.") }
            return
        }
        let defaults = UserDefaults.standard
        defaults.set(Date().timeIntervalSince1970, forKey: UpdateKey.lastCheck)
        if let candidate = SemVer.parse(release.latestVersion), candidate.isNewer(than: current) {
            let skipped = !manual && defaults.string(forKey: UpdateKey.skipped) == release.latestVersion
            if manual {
                defaults.removeObject(forKey: UpdateKey.skipped)
                updateSessionDismissed = false
            }
            availableUpdate = skipped ? nil : release
            updateStatusMessage = String(localized: "Update available: v\(release.latestVersion)")
        } else {
            availableUpdate = nil
            if manual { updateStatusMessage = String(localized: "CastHop is up to date (v\(version)).") }
        }
    }

    func dismissUpdateForSession() {
        updateSessionDismissed = true
    }

    func skipAvailableUpdate() {
        if let v = availableUpdate?.latestVersion {
            UserDefaults.standard.set(v, forKey: UpdateKey.skipped)
        }
        availableUpdate = nil
        updateSessionDismissed = true
    }

    func openUpdateURL() {
        guard let url = availableUpdate?.url else { return }
        UIApplication.shared.open(url)
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
        discoveryFailed = false
        let browser = ReceiverBrowser()
        browser.onChange = { [weak self] found in
            Task { @MainActor in
                self?.discovered = found
                self?.discoveryFailed = false
            }
        }
        browser.onFailed = { [weak self] in
            Task { @MainActor in self?.discoveryFailed = true }
        }
        browser.start()
        self.browser = browser
    }

    func refreshBrowsing() {
        stopBrowsing()
        startBrowsing()
    }

    func stopBrowsing() {
        browser?.stop()
        browser = nil
        discovered = []
    }

    func discovered(id: String) -> DiscoveredReceiver? { discovered.first { $0.id == id } }

    func rememberedMatch(for found: DiscoveredReceiver) -> RememberedReceiver? {
        receivers.first { BoardIdentity.matchesPrefix(fingerprint: $0.fingerprint, prefix: found.fingerprintPrefix) }
    }

    /// Passive Bonjour presence on the Home screen: keeps remembered board IPs fresh and marks boards online.
    func startPassivePresence() {
        guard passiveBrowser == nil, !receivers.isEmpty else { return }
        let browser = ReceiverBrowser()
        browser.onChange = { [weak self] found in
            Task { @MainActor in self?.applyPassiveDiscovery(found) }
        }
        browser.start()
        passiveBrowser = browser
    }

    func stopPassivePresence() {
        passiveBrowser?.stop()
        passiveBrowser = nil
    }

    private func applyPassiveDiscovery(_ found: [DiscoveredReceiver]) {
        guard let store else { return }
        var online = Set<Data>()
        var hostsUpdated = false
        for receiver in receivers {
            if let hit = found.first(where: { BoardIdentity.matchesPrefix(fingerprint: receiver.fingerprint, prefix: $0.fingerprintPrefix) }) {
                online.insert(receiver.fingerprint)
                if !hit.hosts.isEmpty {
                    store.updateHosts(hit.hosts, port: receiver.port, fingerprint: receiver.fingerprint)
                    hostsUpdated = true
                }
            }
        }
        if onlineFingerprints != online { onlineFingerprints = online }
        if hostsUpdated { reload() }
    }

    /// Online boards on the current Wi-Fi float to the top, preserving favorite and recent order inside each group.
    var sortedReceivers: [RememberedReceiver] {
        guard !onlineFingerprints.isEmpty else { return receivers }
        let online = receivers.filter { onlineFingerprints.contains($0.fingerprint) }
        let offline = receivers.filter { !onlineFingerprints.contains($0.fingerprint) }
        return online + offline
    }

    // MARK: Connecting

    func connect(qr: QrPayload) {
        path = []
        lastRememberedFingerprint = nil
        pair(.qr(qr), name: qr.name.isEmpty ? (qr.hosts.first ?? "") : qr.name, keepRoute: false)
    }

    func connect(_ found: DiscoveredReceiver, pin: String) {
        stopBrowsing()
        lastRememberedFingerprint = nil
        let name = rememberedMatch(for: found)?.displayName ?? found.name
        pair(.discovered(found, pin: pin), name: name, keepRoute: true)
    }

    func connect(host: String, port: UInt16, pin: String) {
        lastRememberedFingerprint = nil
        pair(.manual(host: host, port: port, pin: pin), name: host, keepRoute: true)
    }

    func retryLast() {
        if let fp = lastRememberedFingerprint,
           let fresh = store?.receivers().first(where: { $0.fingerprint == fp }) {
            connect(fresh)
            return
        }
        guard let last = lastPair else { return }
        // Avoid resending a spent single-use QR token if the pairing was already persisted.
        if case .qr(let qr) = last.target,
           let saved = store?.receivers().first(where: { $0.fingerprint == qr.certFingerprint }) {
            connect(saved)
            return
        }
        pair(last.target, name: last.name, keepRoute: last.keepRoute)
    }

    func connect(_ receiver: RememberedReceiver) {
        guard let store else { return }
        lastPair = nil
        lastRememberedFingerprint = receiver.fingerprint
        store.selectedFingerprint = receiver.fingerprint
        store.touch(fingerprint: receiver.fingerprint, nowMs: Self.nowMs)
        reload()
        let fresh = receivers.first { $0.fingerprint == receiver.fingerprint } ?? receiver
        phase = .ready(fresh)
        // Non-blocking Bonjour IP pre-warm while the teacher reads the ReadyCard so Broadcast Extension gets a fresh IP.
        prewarmTask?.cancel()
        prewarmTask = Task { [weak self] in
            guard let hit = await ReceiverBrowser.find(fingerprint: fresh.fingerprint, timeoutMs: 2500),
                  !Task.isCancelled, !hit.hosts.isEmpty else { return }
            store.updateHosts(hit.hosts, port: fresh.port, fingerprint: fresh.fingerprint)
            await MainActor.run {
                self?.reload()
                if case .ready(let cur) = self?.phase, cur.fingerprint == fresh.fingerprint,
                   let updated = self?.receivers.first(where: { $0.fingerprint == fresh.fingerprint }) {
                    self?.phase = .ready(updated)
                }
            }
        }
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func pair(_ target: ConnectTarget, name: String, keepRoute: Bool) {
        guard let store, let device else {
            phase = .ended(failure: .internalError)
            return
        }
        pairing?.cancel()
        lastPair = (target, name, keepRoute)
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
                self.path = []
                self.phase = .ready(receiver)
            case .failure(let failure):
                self.reload()
                self.phase = .ended(failure: failure)
            }
        }
    }

    func toggleFavorite(_ receiver: RememberedReceiver) {
        store?.setFavorite(!receiver.favorite, fingerprint: receiver.fingerprint)
        reload()
    }

    func rename(_ receiver: RememberedReceiver, nickname: String?) {
        store?.setNickname(nickname, fingerprint: receiver.fingerprint)
        reload()
    }

    func forget(_ receiver: RememberedReceiver) {
        store?.remove(fingerprint: receiver.fingerprint)
        reload()
    }

    func dismiss() {
        pairing?.cancel()
        prewarmTask?.cancel()
        phase = .idle
    }

    // MARK: Broadcast

    func stop() {
        if let cameraEngine {
            cameraEngine.stop()
        } else {
            DarwinNotifier.post(.stop)
        }
    }

    func pause() {
        if let cameraEngine {
            cameraPaused = true
            cameraEngine.pause()
        } else {
            DarwinNotifier.post(.pause)
        }
    }

    func unpause() {
        if let cameraEngine {
            cameraPaused = false
            cameraEngine.unpause()
        } else {
            DarwinNotifier.post(.unpause)
        }
    }

    // MARK: Camera

    /// A Broadcast Upload Extension cannot use the camera, so this session runs in the app, and only while it is open.
    func startCamera() async {
        guard let store, camera == nil else { return }
        guard await AVCaptureDevice.requestAccess(for: .video) else {
            phase = .ended(failure: .cameraDenied)
            return
        }
        let capture = CameraCapture()
        let engine = BroadcastEngine(store: store, device: .init(name: UIDevice.current.name, appVersion: version), sendsAudio: false)
        capture.onFrame = { [weak engine] image, ptsUs in engine?.video(image, rotation: .none, ptsUs: ptsUs) }
        capture.onInterruption = { [weak self] interrupted in
            Task { @MainActor in self?.cameraInterrupted(interrupted) }
        }
        capture.onFailure = { [weak engine] in engine?.stop(reason: ByeReason.error, failure: .cameraFailed) }
        engine.onFinished = { [weak self] _ in
            Task { @MainActor in self?.cameraEnded() }
        }
        cameraPaused = false
        cameraEngine = engine
        camera = capture
        UIApplication.shared.isIdleTimerDisabled = true
        engine.start()
        capture.start()
    }

    func switchCamera() { camera?.switchPosition() }

    private func cameraInterrupted(_ interrupted: Bool) {
        if interrupted {
            cameraEngine?.pause()
        } else if !cameraPaused {
            cameraEngine?.unpause()
        }
    }

    private func cameraEnded() {
        camera?.stop()
        camera = nil
        cameraEngine = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// iOS takes the camera away from an app in the background and suspends it soon after.
    func enteredBackground() {
        cameraEngine?.stop(failure: .cameraLeft)
    }

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
        if camera == nil, running.contains(store.status.phase), !UIScreen.main.isCaptured {
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
