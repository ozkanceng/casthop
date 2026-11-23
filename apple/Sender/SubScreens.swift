import AVFoundation
import CastHopProtocol
import CastHopSender
import SwiftUI
import UIKit

/// Step lists shared by the guide and the error cards.
enum GuideSteps {
    static let qr: [LocalizedStringKey] = [
        "On the tablet, tap \"Connect to board\" (\"New board with QR\" if you already have remembered boards).",
        "Point the camera at the QR code on the board.",
        "Tap \"Start mirroring\", then \"Start Broadcast\" in the system window.",
    ]
    static let code: [LocalizedStringKey] = [
        "Tap \"Connect with code\".",
        "Pick your board from the boards on the same network.",
        "Enter the 6-digit code shown on the board.",
        "Tap \"Start mirroring\", then \"Start Broadcast\" in the system window.",
    ]
    static let remembered: [LocalizedStringKey] = [
        "A board you have connected to appears under \"Remembered boards\" on the home screen.",
        "Tap its name, then \"Start mirroring\". CastHop must be open on the board.",
    ]
    static let hotspot: [LocalizedStringKey] = [
        "On the tablet, open Settings → Personal Hotspot and turn on Allow Others to Join.",
        "On the board, join the tablet's network from the network icon.",
        "Scan the new QR code that appears on the board.",
    ]
}

func boardKindIcon(_ kind: String?) -> String {
    switch kind {
    case "pc": return "desktopcomputer"
    case "tablet": return "ipad.landscape"
    case "board": return "rectangle.inset.filled.and.person.filled"
    default: return "tv"
    }
}

struct GuideView: View {
    @EnvironmentObject private var model: AppModel

    private let prepare: [LocalizedStringKey] = [
        "Open CastHop on the board. A QR code and a 6-digit code appear.",
        "Keep the tablet and the board on the same Wi-Fi network.",
        "On the first connection the app shows which permissions it needs and why.",
    ]
    private let during: [LocalizedStringKey] = [
        "\"Pause\" keeps the last picture on the board while you do something else on the tablet.",
        "To stop, tap \"Stop mirroring\" or the red indicator in the status bar.",
        "If Wi-Fi drops briefly, mirroring recovers by itself.",
    ]
    private let faq: [(LocalizedStringKey, LocalizedStringKey)] = [
        ("Cannot reach the board",
         "The tablet and the board must be on the same Wi-Fi network. The school network may block devices from seeing each other: try turning on the tablet's hotspot and connecting the board to it."),
        ("CastHop is not running on the board", "Open the CastHop app on the board and scan the new QR code."),
        ("The QR code is no longer valid", "Each QR code works only once. Scan the new QR code on the board."),
        ("The picture stutters or looks blurry",
         "In Settings, set picture quality to \"Balanced\" or \"Weak network\". Prefer 5 GHz Wi-Fi."),
        ("There is no sound",
         "Check that \"Play sound on the board\" is on in Settings. Some apps do not allow their audio to be shared."),
        ("Mirroring stops when the screen locks", "iOS ends a broadcast when the device locks. Increase the Auto-Lock time."),
    ]

    private var first: some View {
        VStack(spacing: Tokens.space4) {
            SectionCard(title: "Before you start", icon: "checklist") { CheckList(items: prepare) }
            SectionCard(title: "Connect with the QR code (recommended)", icon: "qrcode.viewfinder") {
                StepList(steps: GuideSteps.qr)
                Button("Scan the QR code") { model.openScanner() }
                    .buttonStyle(FilledButtonStyle())
            }
            SectionCard(title: "Connect with the code (no camera)", icon: "number") {
                StepList(steps: GuideSteps.code)
                Button("Connect with code (no QR)") { model.path.append(.discover) }
                    .buttonStyle(OutlineButtonStyle())
            }
        }
    }

    private var second: some View {
        VStack(spacing: Tokens.space4) {
            SectionCard(title: "In later lessons", icon: "clock.arrow.circlepath") { StepList(steps: GuideSteps.remembered) }
            SectionCard(title: "If the school network blocks it: use the tablet's hotspot", icon: "personalhotspot") {
                StepList(steps: GuideSteps.hotspot)
            }
            SectionCard(title: "While mirroring", icon: "play.circle") { CheckList(items: during) }
            SectionCard(title: "Common problems", icon: "exclamationmark.triangle") {
                ForEach(faq.indices, id: \.self) { index in
                    if index > 0 { Divider() }
                    DisclosureGroup {
                        Text(faq[index].1)
                            .font(.body)
                            .foregroundStyle(Tokens.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } label: {
                        Text(faq[index].0)
                            .font(.headline)
                            .foregroundStyle(Tokens.onSurface)
                            .frame(minHeight: Tokens.targetSmall)
                    }
                }
            }
        }
    }

    var body: some View {
        SubScreen(title: "How do I connect?", maxWidth: 1120) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: Tokens.space4) {
                    first.frame(minWidth: 340)
                    second.frame(minWidth: 340)
                }
                VStack(spacing: Tokens.space4) {
                    first
                    second
                }
            }
        }
    }
}

/// Why the app asks, before iOS does.
struct PermissionsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL

    private func item(_ title: LocalizedStringKey, _ reason: LocalizedStringKey, icon: String) -> some View {
        HStack(alignment: .top, spacing: Tokens.space4) {
            IconBadge(systemName: icon)
            VStack(alignment: .leading, spacing: Tokens.space1) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(Tokens.onSurface)
                Text(reason)
                    .font(.body)
                    .foregroundStyle(Tokens.onSurfaceVariant)
            }
        }
    }

    var body: some View {
        SubScreen(title: "A few permissions are needed first") {
            SectionCard(title: "Before you start", icon: "hand.raised") {
                item("Camera", "To read the QR code on the board. Nothing is recorded.", icon: "camera")
                Divider()
                item("Local network", "To connect to the board on the same network.", icon: "wifi")
            }
            Button("Continue") {
                Task { await model.requestPermissions() }
            }
            .buttonStyle(FilledButtonStyle())
            if model.cameraDenied {
                Text("Some permissions were not granted. You can enable them in Settings.")
                    .font(.subheadline)
                    .foregroundStyle(Tokens.onSurfaceVariant)
                Button("Open settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(OutlineButtonStyle())
            }
        }
    }
}

/// Camera preview that reports each QR code it reads and supports toggling the torch in dark classrooms.
private final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onFailure: (() -> Void)?
    var onTorchAvailability: ((Bool) -> Void)?
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "casthop.scanner")
    private var preview: AVCaptureVideoPreviewLayer?
    private var captureDevice: AVCaptureDevice?

    func setTorch(_ on: Bool) {
        queue.async { [weak self] in
            guard let device = self?.captureDevice, device.hasTorch else { return }
            do {
                try device.lockForConfiguration()
                device.torchMode = on ? .on : .off
                device.unlockForConfiguration()
            } catch {}
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let output = AVCaptureMetadataOutput()
        guard let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input), session.canAddOutput(output) else {
            DispatchQueue.main.async { [weak self] in self?.onFailure?() }
            return
        }
        captureDevice = camera
        DispatchQueue.main.async { [weak self] in self?.onTorchAvailability?(camera.hasTorch) }
        session.addInput(input)
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        guard let connection = preview?.connection else { return }
        let angle: CGFloat
        switch view.window?.windowScene?.interfaceOrientation {
        case .landscapeLeft: angle = 180
        case .landscapeRight: angle = 0
        case .portraitUpsideDown: angle = 270
        default: angle = 90
        }
        if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        queue.async { [session] in session.startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        setTorch(false)
        queue.async { [session] in session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        for case let code as AVMetadataMachineReadableCodeObject in objects {
            if let text = code.stringValue { onCode?(text) }
        }
    }
}

private struct ScannerPreview: UIViewControllerRepresentable {
    let torchOn: Bool
    let onCode: (String) -> Void
    let onFailure: () -> Void
    let onTorchAvailability: (Bool) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onCode = onCode
        controller.onFailure = onFailure
        controller.onTorchAvailability = onTorchAvailability
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {
        controller.setTorch(torchOn)
    }
}

struct ScannerView: View {
    @EnvironmentObject private var model: AppModel
    @State private var hint: LocalizedStringKey = "Point the camera at the QR code on the board"
    @State private var done = false
    @State private var torchAvailable = false
    @State private var torchOn = false
    @State private var scanLineDown = false

    var body: some View {
        ZStack {
            ScannerPreview(torchOn: torchOn, onCode: { text in
                guard !done else { return }
                if let payload = try? QrPayload.parse(text) {
                    done = true
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    model.connect(qr: payload)
                } else {
                    hint = "This is not a CastHop code. Scan the QR code on the board."
                }
            }, onFailure: {
                hint = "The camera could not be opened."
            }, onTorchAvailability: { available in
                torchAvailable = available
            })
            .ignoresSafeArea()

            // Corner viewfinder + animated laser line
            ZStack {
                ScanFrameCorners()
                Capsule()
                    .fill(LinearGradient(colors: [.clear, Tokens.primary, .white, Tokens.primary, .clear],
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(height: 3)
                    .padding(.horizontal, 16)
                    .offset(y: scanLineDown ? 105 : -105)
            }
            .frame(width: 260, height: 260)
            .accessibilityHidden(true)

            VStack {
                // Top bar: Back + Torch
                HStack {
                    Button {
                        model.path.removeLast()
                    } label: {
                        Image(systemName: "chevron.backward")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(width: 48, height: 48)
                            .background(.black.opacity(0.55), in: Circle())
                    }
                    .accessibilityLabel(Text("Back"))
                    Spacer()
                    if torchAvailable {
                        Button {
                            torchOn.toggle()
                        } label: {
                            Image(systemName: torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(torchOn ? Color.yellow : .white)
                                .frame(width: 48, height: 48)
                                .background(.black.opacity(0.55), in: Circle())
                        }
                        .accessibilityLabel(Text(torchOn ? "Turn flashlight off" : "Turn flashlight on"))
                    }
                }
                .padding(.horizontal, Tokens.space4)
                .padding(.top, Tokens.space2)

                Spacer()

                VStack(spacing: Tokens.space3) {
                    Text(hint)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                    HStack(spacing: Tokens.space3) {
                        Button {
                            model.path[model.path.count - 1] = .discover
                        } label: {
                            Label("With code", systemImage: "number")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity, minHeight: Tokens.targetSmall)
                                .background(.white.opacity(0.16), in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
                                .overlay(RoundedRectangle(cornerRadius: Tokens.radiusSmall).stroke(.white.opacity(0.35), lineWidth: 1))
                        }
                        Button {
                            model.path[model.path.count - 1] = .manualIp
                        } label: {
                            Label("With IP", systemImage: "network")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity, minHeight: Tokens.targetSmall)
                                .background(.white.opacity(0.16), in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
                                .overlay(RoundedRectangle(cornerRadius: Tokens.radiusSmall).stroke(.white.opacity(0.35), lineWidth: 1))
                        }
                    }
                }
                .padding(Tokens.space5)
                .frame(maxWidth: .infinity)
                .background(.black.opacity(0.72))
            }
        }
        .navigationBarHidden(true)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.7).repeatForever(autoreverses: true)) {
                scanLineDown = true
            }
        }
    }
}

private struct ScanFrameCorners: View {
    var body: some View {
        Canvas { ctx, size in
            let arm = size.width * 0.18
            let lineWidth: CGFloat = 5
            let corners: [(CGPoint, CGPoint)] = [
                (CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 1)),
                (CGPoint(x: size.width, y: 0), CGPoint(x: -1, y: 1)),
                (CGPoint(x: 0, y: size.height), CGPoint(x: 1, y: -1)),
                (CGPoint(x: size.width, y: size.height), CGPoint(x: -1, y: -1)),
            ]
            for (pt, dir) in corners {
                var p = Path()
                p.move(to: CGPoint(x: pt.x + arm * dir.x, y: pt.y))
                p.addLine(to: pt)
                p.addLine(to: CGPoint(x: pt.x, y: pt.y + arm * dir.y))
                ctx.stroke(p, with: .color(.white), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
            }
        }
    }
}

struct DiscoverView: View {
    @EnvironmentObject private var model: AppModel
    @State private var timedOut = false
    @State private var searchGeneration = 0

    private var sorted: [DiscoveredReceiver] {
        BoardIdentity.sortDiscovered(model.discovered, remembered: model.receivers)
    }

    var body: some View {
        SubScreen(title: "Boards on this network") {
            HStack(spacing: Tokens.space3) {
                ProgressView()
                Text("Searching for boards on the same network…")
                    .font(.body)
                    .foregroundStyle(Tokens.onSurface)
                Spacer()
                Button {
                    timedOut = false
                    searchGeneration += 1
                    model.refreshBrowsing()
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                        .font(.subheadline.weight(.semibold))
                }
                .frame(minHeight: Tokens.targetSmall)
            }

            if sorted.isEmpty {
                if !timedOut && !model.discoveryFailed {
                    BoardSkeletonList(count: 3)
                }
                if timedOut || model.discoveryFailed {
                    VStack(alignment: .leading, spacing: Tokens.space3) {
                        Text("No board appeared yet")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(Tokens.onSurface)
                        Text("Mobile hotspots or school networks may block automatic discovery (mDNS). You can connect directly using the IP address or QR code on the board.")
                            .font(.body)
                            .foregroundStyle(Tokens.onSurfaceVariant)
                        Button {
                            model.path.append(.manualIp)
                        } label: {
                            Label("Connect by IP address", systemImage: "network")
                        }
                        .buttonStyle(FilledButtonStyle())
                        Button {
                            model.openScanner()
                        } label: {
                            Label("Scan the QR code", systemImage: "qrcode.viewfinder")
                        }
                        .buttonStyle(OutlineButtonStyle())
                    }
                    .padding(Tokens.space4 + Tokens.space1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
                    .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            } else {
                VStack(spacing: Tokens.space3) {
                    ForEach(sorted) { found in
                        let saved = model.rememberedMatch(for: found)
                        let title = saved?.displayName ?? found.name
                        let subtitle = BoardIdentity.subtitle(hosts: saved?.hosts ?? [], fingerprintOrPrefix: found.fingerprintPrefix,
                                                              rawName: found.name, nickname: saved?.nickname)
                        NavigationCard(
                            title: Text(verbatim: title),
                            subtitle: Text(verbatim: subtitle),
                            icon: boardKindIcon(found.kind ?? saved?.kind),
                            badgeSystemName: saved?.favorite == true ? "star.fill" : nil
                        ) {
                            model.dismiss()
                            model.path.append(.pin(found.id))
                        }
                    }
                }
            }

            NavigationCard(
                title: Text("Not listed? Connect by IP address"),
                subtitle: Text("Enter the IP address and 6-digit code shown on the board."),
                icon: "network"
            ) {
                model.dismiss()
                model.path.append(.manualIp)
            }

            Text("Board not listed? Is CastHop open on the board and the tablet on the same network? School networks may block discovery; the QR code always works.")
                .font(.subheadline)
                .foregroundStyle(Tokens.onSurfaceVariant)
        }
        .onAppear { model.startBrowsing() }
        .task(id: "\(searchGeneration)-\(model.discovered.isEmpty)") {
            guard model.discovered.isEmpty else {
                timedOut = false
                return
            }
            timedOut = false
            try? await Task.sleep(for: .seconds(5))
            if !Task.isCancelled && model.discovered.isEmpty {
                withAnimation { timedOut = true }
            }
        }
    }
}

struct PinView: View {
    let id: String
    @EnvironmentObject private var model: AppModel
    @State private var pin = ""
    @State private var errorSignal = 0

    private var found: DiscoveredReceiver? { model.discovered(id: id) }
    private var remembered: RememberedReceiver? { found.flatMap { model.rememberedMatch(for: $0) } }
    private var displayTitle: String { remembered?.displayName ?? found?.name ?? "" }
    private var subtitle: String {
        guard let found else { return "" }
        return BoardIdentity.subtitle(hosts: remembered?.hosts ?? [], fingerprintOrPrefix: found.fingerprintPrefix,
                                      rawName: found.name, nickname: remembered?.nickname)
    }
    private var failure: MirrorFailure? {
        if case .ended(let f) = model.phase { return f }
        return nil
    }
    private var visualState: OtpVisualState {
        if model.phase.isConnectingFlow { return .submitting }
        if failure == .wrongPin || failure == .lockedOut { return .error }
        return .idle
    }

    var body: some View {
        SubScreen(title: "Connect with code (no QR)", maxWidth: 520) {
            if let found {
                // Selected board identity summary card
                HStack(spacing: Tokens.space4) {
                    IconBadge(systemName: boardKindIcon(found.kind ?? remembered?.kind))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: displayTitle)
                            .font(.headline)
                            .foregroundStyle(Tokens.onSurface)
                        Text(verbatim: subtitle)
                            .font(.subheadline.monospaced())
                            .foregroundStyle(Tokens.onSurfaceVariant)
                    }
                    Spacer()
                }
                .padding(Tokens.space4)
                .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
                .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
            }

            if model.phase.isConnectingFlow {
                let waiting = {
                    if case .pairingApproval = model.phase { return true }
                    return false
                }()
                ConnectionStepperCard(
                    boardName: displayTitle,
                    activeStep: 1,
                    waitingApproval: waiting,
                    onCancel: { model.dismiss() }
                )
            } else {
                VStack(spacing: Tokens.space4) {
                    Text("Enter the 6-digit code shown on the board.")
                        .font(.headline)
                        .foregroundStyle(Tokens.onSurface)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)

                    OtpCodeInput(
                        code: $pin,
                        visualState: visualState,
                        enabled: found != nil,
                        errorSignal: errorSignal,
                        autoFocus: true
                    ) { code in
                        if let found { model.connect(found, pin: code) }
                    }

                    HStack {
                        Text("Connection starts automatically after the 6th digit.")
                            .font(.subheadline)
                            .foregroundStyle(Tokens.onSurfaceVariant)
                        Spacer()
                        if !pin.isEmpty {
                            Button("Clear") { pin = "" }
                                .font(.subheadline.weight(.semibold))
                                .frame(minHeight: Tokens.targetSmall)
                        }
                    }

                    if let failure {
                        ConnectionErrorCard(
                            failure: failure,
                            onRetry: {
                                if failure == .wrongPin {
                                    pin = ""
                                } else if let found, pin.count == 6 {
                                    model.connect(found, pin: pin)
                                } else {
                                    model.retryLast()
                                }
                            },
                            onManualIp: {
                                model.dismiss()
                                model.path.append(.manualIp)
                            },
                            onGuide: {
                                model.dismiss()
                                model.path.append(.guide)
                            }
                        )
                    }

                    Button("Connect to board") {
                        if let found { model.connect(found, pin: pin) }
                    }
                    .buttonStyle(FilledButtonStyle())
                    .disabled(pin.count != 6 || found == nil)

                    BoardCodeIllustrationCard()
                }
            }
        }
        .onChange(of: model.phase) { _, newPhase in
            if case .ended(let err) = newPhase, let err {
                errorSignal += 1
                if err == .wrongPin {
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(380))
                        pin = ""
                    }
                }
            }
        }
        .onDisappear {
            if model.phase.isConnectingFlow || failure != nil { model.dismiss() }
        }
    }
}

/// Direct IPv4 + 6-digit OTP connection screen for hotspots and mDNS-blocked school networks.
struct ManualIpView: View {
    @EnvironmentObject private var model: AppModel
    @State private var host = ""
    @State private var portText = "47800"
    @State private var showPort = false
    @State private var pin = ""
    @State private var errorSignal = 0

    private var cleanHost: String { host.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var hostValid: Bool { BoardIdentity.isValidIpv4(cleanHost) }
    private var validPort: UInt16? { UInt16(portText.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0 > 0 ? $0 : nil } }
    private var canEnterCode: Bool { hostValid && validPort != nil }
    private var failure: MirrorFailure? {
        if case .ended(let f) = model.phase { return f }
        return nil
    }

    var body: some View {
        SubScreen(title: "Connect by IP address", maxWidth: 520) {
            if model.phase.isConnectingFlow {
                let waiting = {
                    if case .pairingApproval = model.phase { return true }
                    return false
                }()
                ConnectionStepperCard(
                    boardName: cleanHost,
                    activeStep: 1,
                    waitingApproval: waiting,
                    onCancel: { model.dismiss() }
                )
            } else {
                VStack(alignment: .leading, spacing: Tokens.space4) {
                    Text("Enter the IP address and 6-digit code shown on the board.")
                        .font(.body)
                        .foregroundStyle(Tokens.onSurfaceVariant)

                    VStack(alignment: .leading, spacing: Tokens.space2) {
                        Text("Board IP address")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Tokens.onSurface)
                        HStack {
                            TextField("192.168.43.102", text: $host)
                                .keyboardType(.decimalPad)
                                .font(.title3.weight(.semibold).monospaced())
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .onChange(of: host) { _, v in
                                    let clean = String(v.filter { $0.isNumber || $0 == "." }.prefix(15))
                                    if clean != v { host = clean }
                                }
                            if !host.isEmpty {
                                Button {
                                    host = ""
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(Tokens.onSurfaceVariant)
                                }
                                .accessibilityLabel(Text("Clear"))
                            }
                        }
                        .padding(Tokens.space3)
                        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
                        .overlay(
                            RoundedRectangle(cornerRadius: Tokens.radiusSmall)
                                .stroke(!host.isEmpty && !hostValid ? Tokens.error : Tokens.outlineVariant, lineWidth: 1.5)
                        )
                        if !host.isEmpty && !hostValid {
                            Text("Enter a valid IPv4 address (e.g. 192.168.43.102).")
                                .font(.caption)
                                .foregroundStyle(Tokens.error)
                        }
                    }

                    if !model.recentIps.isEmpty {
                        VStack(alignment: .leading, spacing: Tokens.space2) {
                            Text("Recent addresses")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Tokens.onSurfaceVariant)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Tokens.space2) {
                                    ForEach(model.recentIps, id: \.self) { ip in
                                        Button {
                                            host = ip
                                        } label: {
                                            Text(verbatim: ip)
                                                .font(.subheadline.monospaced())
                                                .foregroundStyle(Tokens.onSurface)
                                                .padding(.horizontal, 12)
                                                .frame(minHeight: 40)
                                                .background(Tokens.surfaceContainer, in: Capsule())
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }

                    DisclosureGroup(isExpanded: $showPort) {
                        VStack(alignment: .leading, spacing: Tokens.space1) {
                            TextField("47800", text: $portText)
                                .keyboardType(.numberPad)
                                .font(.headline.monospaced())
                                .padding(Tokens.space3)
                                .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
                                .overlay(
                                    RoundedRectangle(cornerRadius: Tokens.radiusSmall)
                                        .stroke(validPort == nil ? Tokens.error : Tokens.outlineVariant, lineWidth: 1.5)
                                )
                                .onChange(of: portText) { _, v in
                                    let clean = String(v.filter(\.isNumber).prefix(5))
                                    if clean != v { portText = clean }
                                }
                            if validPort == nil {
                                Text("Port must be between 1 and 65535.")
                                    .font(.caption)
                                    .foregroundStyle(Tokens.error)
                            }
                        }
                        .padding(.top, Tokens.space2)
                    } label: {
                        Text("Change port (default 47800)")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Tokens.primary)
                            .frame(minHeight: Tokens.targetSmall)
                    }

                    Text("6-digit code on the board")
                        .font(.headline)
                        .foregroundStyle(Tokens.onSurface)

                    OtpCodeInput(
                        code: $pin,
                        visualState: failure == .wrongPin || failure == .lockedOut ? .error : .idle,
                        enabled: canEnterCode,
                        errorSignal: errorSignal,
                        autoFocus: false
                    ) { code in
                        if let port = validPort, hostValid {
                            model.connect(host: cleanHost, port: port, pin: code)
                        }
                    }

                    if let failure {
                        ConnectionErrorCard(
                            failure: failure,
                            onRetry: {
                                if failure == .wrongPin {
                                    pin = ""
                                } else if let port = validPort, hostValid, pin.count == 6 {
                                    model.connect(host: cleanHost, port: port, pin: pin)
                                } else {
                                    model.retryLast()
                                }
                            },
                            onGuide: {
                                model.dismiss()
                                model.path.append(.guide)
                            }
                        )
                    }

                    Button("Connect to board") {
                        if let port = validPort, hostValid {
                            model.connect(host: cleanHost, port: port, pin: pin)
                        }
                    }
                    .buttonStyle(FilledButtonStyle())
                    .disabled(!canEnterCode || pin.count != 6)

                    BoardCodeIllustrationCard()
                }
            }
        }
        .onAppear {
            if host.isEmpty, let first = model.recentIps.first { host = first }
        }
        .onChange(of: model.phase) { _, newPhase in
            if case .ended(let err) = newPhase, let err {
                errorSignal += 1
                if err == .wrongPin {
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(380))
                        pin = ""
                    }
                }
            }
        }
        .onDisappear {
            if model.phase.isConnectingFlow || failure != nil { model.dismiss() }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    private func presetRow(_ preset: Preset, _ title: LocalizedStringKey, _ detail: LocalizedStringKey) -> some View {
        Button {
            model.settings.preset = preset
        } label: {
            HStack(spacing: Tokens.space3) {
                Image(systemName: model.settings.preset == preset ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(Tokens.primary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: Tokens.space1) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Tokens.onSurface)
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(Tokens.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: Tokens.targetMedium)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(model.settings.preset == preset ? .isSelected : [])
    }

    private func toggleRow(_ title: LocalizedStringKey, _ detail: LocalizedStringKey, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: Tokens.space1) {
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Tokens.onSurface)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(Tokens.onSurfaceVariant)
            }
        }
        .tint(Tokens.primary)
        .frame(minHeight: Tokens.targetMedium)
    }

    /// "Auto" and the supported values. Unsupported ones are not shown.
    private func choiceRow<T: Hashable>(_ label: LocalizedStringKey, values: [T], selected: T?, format: @escaping (T) -> String,
                                        onSelect: @escaping (T?) -> Void) -> some View {
        VStack(alignment: .leading, spacing: Tokens.space2) {
            Text(label)
                .font(.body)
                .foregroundStyle(Tokens.onSurface)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Tokens.space2) {
                    chip(Text("Auto"), selected: selected == nil) { onSelect(nil) }
                    ForEach(values, id: \.self) { value in
                        chip(Text(verbatim: format(value)), selected: selected == value) { onSelect(value) }
                    }
                }
            }
        }
    }

    private func chip(_ label: Text, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            label
                .font(.subheadline.weight(.medium))
                .foregroundStyle(selected ? Tokens.onPrimary : Tokens.onSurface)
                .padding(.horizontal, Tokens.space4)
                .frame(minHeight: Tokens.targetSmall)
                .background(selected ? Tokens.primary : Tokens.surfaceContainer, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    var body: some View {
        let options = model.encoderOptions
        SubScreen(title: "Settings") {
            if case .broadcast = model.phase {
                // Settings are read when a broadcast starts.
                Text("Changes take effect the next time you mirror.")
                    .font(.body)
                    .foregroundStyle(Tokens.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            SectionCard(title: "Picture quality", icon: "sparkles.tv") {
                presetRow(.best, "Best quality", "1080p, 60 fps. 5 GHz Wi-Fi recommended.")
                presetRow(.balanced, "Balanced", "Lower bitrate, smooth motion.")
                presetRow(.weakNetwork, "Weak network", "720p, 30 fps for crowded or 2.4 GHz networks.")
                presetRow(.ultraLow, "Very weak network / Hotspot", "540p, 24 fps. Smoothest choice on congested or slow mobile hotspots.")
                Divider()
                toggleRow("Play sound on the board",
                          "Video and app sounds play from the board's speakers. Some apps (e.g. calls, some video apps) do not allow their sound to be shared.",
                          isOn: $model.settings.audioEnabled)
            }
            SectionCard(title: "Advanced", icon: "slider.horizontal.3") {
                Text("Only values supported by this tablet are shown. The board and network may still lower them.")
                    .font(.subheadline)
                    .foregroundStyle(Tokens.onSurfaceVariant)
                choiceRow("Maximum resolution", values: options.longSides, selected: model.settings.overrides.longSide,
                          format: { "\($0) px" }) { model.settings.overrides.longSide = $0 }
                choiceRow("Frame rate", values: options.fps, selected: model.settings.overrides.fps,
                          format: { "\($0) fps" }) { model.settings.overrides.fps = $0 }
                if options.hevc {
                    toggleRow("Use H.265 (experimental)",
                              "If the board can decode it in hardware, the same quality needs less data. Otherwise H.264 is used.",
                              isOn: $model.settings.hevcEnabled)
                }
                choiceRow("Maximum bitrate", values: AppModel.bitrateOptions, selected: model.settings.overrides.maxBitrateBps,
                          format: { "\($0 / 1_000_000) Mbps" }) { model.settings.overrides.maxBitrateBps = $0 }
            }
        }
    }
}
