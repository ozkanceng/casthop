import AVFoundation
import CastHopProtocol
import CastHopSender
import SwiftUI

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
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Tokens.primary)
                .frame(width: 44, height: 44)
                .background(Tokens.surfaceContainer, in: Circle())
                .accessibilityHidden(true)
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
            item("Camera", "To read the QR code on the board. Nothing is recorded.", icon: "camera")
            item("Local network", "To connect to the board on the same network.", icon: "wifi")
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

/// Camera preview that reports each QR code it reads.
private final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onFailure: (() -> Void)?
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "casthop.scanner")
    private var preview: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let output = AVCaptureMetadataOutput()
        guard let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input), session.canAddOutput(output) else {
            DispatchQueue.main.async { [weak self] in self?.onFailure?() }
            return
        }
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
        queue.async { [session] in session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        for case let code as AVMetadataMachineReadableCodeObject in objects {
            if let text = code.stringValue { onCode?(text) }
        }
    }
}

private struct ScannerPreview: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onFailure: () -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onCode = onCode
        controller.onFailure = onFailure
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}
}

struct ScannerView: View {
    @EnvironmentObject private var model: AppModel
    @State private var hint: LocalizedStringKey = "Point the camera at the QR code on the board"
    @State private var done = false

    var body: some View {
        ZStack(alignment: .bottom) {
            ScannerPreview(onCode: { text in
                guard !done else { return }
                if let payload = try? QrPayload.parse(text) {
                    done = true
                    model.connect(qr: payload)
                } else {
                    hint = "This is not a CastHop code. Scan the QR code on the board."
                }
            }, onFailure: {
                hint = "The camera could not be opened."
            })
            .ignoresSafeArea()
            RoundedRectangle(cornerRadius: Tokens.radius)
                .stroke(.white, lineWidth: 4)
                .frame(width: 260, height: 260)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityHidden(true)
            VStack(spacing: Tokens.space3) {
                Text(hint)
                    .font(.title3)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                Button("Cancel") { model.path.removeLast() }
                    .foregroundStyle(.white)
                    .frame(minHeight: Tokens.targetSmall)
            }
            .padding(Tokens.space5)
            .frame(maxWidth: .infinity)
            .background(.black.opacity(0.6))
        }
        .navigationBarHidden(true)
    }
}

struct DiscoverView: View {
    @EnvironmentObject private var model: AppModel

    private static func icon(_ kind: String?) -> String {
        switch kind {
        case "pc": return "desktopcomputer"
        case "tablet": return "ipad.landscape"
        case "board": return "rectangle.inset.filled.and.person.filled"
        default: return "tv"
        }
    }

    var body: some View {
        SubScreen(title: "Boards on this network") {
            HStack(spacing: Tokens.space3) {
                ProgressView()
                Text("Searching for boards on the same network…")
                    .font(.body)
                    .foregroundStyle(Tokens.onSurface)
            }
            ForEach(model.discovered) { found in
                NavigationCard(title: Text(verbatim: found.name), icon: Self.icon(found.kind)) { model.path.append(.pin(found.id)) }
            }
            Text("Board not listed? Is CastHop open on the board and the tablet on the same network? School networks may block discovery; the QR code always works.")
                .font(.subheadline)
                .foregroundStyle(Tokens.onSurfaceVariant)
        }
        .onAppear { model.startBrowsing() }
    }
}

struct PinView: View {
    let id: String
    @EnvironmentObject private var model: AppModel
    @State private var pin = ""
    @FocusState private var focused: Bool

    private var found: DiscoveredReceiver? { model.discovered(id: id) }

    var body: some View {
        SubScreen(title: "Connect with code (no QR)", maxWidth: 480) {
            if let found {
                Text(verbatim: found.name)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
                    .frame(maxWidth: .infinity)
            }
            Text("Enter the 6-digit code shown on the board.")
                .font(.body)
                .foregroundStyle(Tokens.onSurface)
                .frame(maxWidth: .infinity)
            TextField("Code", text: $pin)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .font(.system(size: 40, weight: .semibold, design: .monospaced))
                .multilineTextAlignment(.center)
                .padding(Tokens.space3)
                .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
                .focused($focused)
                .onChange(of: pin) { _, value in
                    let digits = String(value.filter(\.isNumber).prefix(6))
                    if digits != value { pin = digits }
                }
            Button("Connect to board") {
                if let found { model.connect(found, pin: pin) }
            }
            .buttonStyle(FilledButtonStyle())
            .disabled(pin.count != 6 || found == nil)
        }
        .onAppear { focused = true }
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
                        .font(.body)
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
                    .font(.body)
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
            SectionCard(title: "Picture quality") {
                presetRow(.best, "Best quality", "1080p, 60 fps. 5 GHz Wi-Fi recommended.")
                presetRow(.balanced, "Balanced", "Lower bitrate, smooth motion.")
                presetRow(.weakNetwork, "Weak network", "720p, 30 fps for crowded or 2.4 GHz networks.")
                Divider()
                toggleRow("Play sound on the board",
                          "Video and app sounds play from the board's speakers. Some apps (e.g. calls, some video apps) do not allow their sound to be shared.",
                          isOn: $model.settings.audioEnabled)
            }
            SectionCard(title: "Advanced") {
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
