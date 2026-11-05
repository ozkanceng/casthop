import CastHopSender
import ReplayKit
import SwiftUI

/// The system's start button. Only it may open the broadcast sheet. It covers the styled label, which stays visible.
private struct BroadcastPicker: UIViewRepresentable {
    static let extensionId = "io.github.ozkanceng.casthop.sender.broadcast"

    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = Self.extensionId
        picker.showsMicrophoneButton = false
        for case let button as UIButton in picker.subviews {
            button.setImage(nil, for: .normal)
            button.setImage(nil, for: .highlighted)
        }
        return picker
    }

    func updateUIView(_ view: RPSystemBroadcastPickerView, context: Context) {
        for case let button as UIButton in view.subviews { button.frame = view.bounds }
    }
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel

    private var idle: Bool {
        switch model.phase {
        case .idle, .ended: return true
        default: return false
        }
    }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                Group {
                    if idle {
                        ConnectView(wide: geometry.size.width >= Tokens.wideLayout)
                    } else {
                        SessionView().frame(maxWidth: Tokens.contentWidth)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(Tokens.space5)
            }
        }
        .background(Tokens.background)
        .navigationTitle(Text(verbatim: "CastHop"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if idle {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { model.path.append(.guide) } label: {
                        Image(systemName: "questionmark.circle")
                    }
                    .accessibilityLabel(Text("How do I connect?"))
                    Button { model.path.append(.settings) } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(Text("Settings"))
                }
            }
        }
    }
}

/// With no connection: what went wrong, the ways to connect, short steps and the guide.
private struct ConnectView: View {
    let wide: Bool
    @EnvironmentObject private var model: AppModel

    private var ended: (failure: MirrorFailure?, shown: Bool) {
        if case .ended(let failure) = model.phase { return (failure, true) }
        return (nil, false)
    }

    private var hero: some View {
        VStack(spacing: Tokens.space4) {
            Image(systemName: "rectangle.on.rectangle.angled")
                .font(.system(size: 72))
                .foregroundStyle(Tokens.primary)
                .accessibilityHidden(true)
            Text("Mirror your tablet screen to the classroom whiteboard, wirelessly.")
                .font(.title2)
                .multilineTextAlignment(.center)
                .foregroundStyle(Tokens.onSurface)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var choices: some View {
        if ended.shown {
            EndedCard(failure: ended.failure)
            Button("OK") { model.dismiss() }
                .frame(minHeight: Tokens.targetSmall)
        }
        VStack(spacing: Tokens.space3) {
            if model.receivers.isEmpty {
                Button {
                    model.openScanner()
                } label: {
                    Label("Connect to board", systemImage: "qrcode.viewfinder")
                }
                .buttonStyle(FilledButtonStyle())
            } else {
                Text("Remembered boards")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(model.receivers) { receiver in
                    HStack(spacing: Tokens.space2) {
                        NavigationCard(title: Text(verbatim: receiver.name), icon: "tv") { model.connect(receiver) }
                        Button("Forget") { model.forget(receiver) }
                            .frame(minHeight: Tokens.targetSmall)
                            .accessibilityLabel(Text("Forget \(receiver.name)"))
                    }
                }
                Text("Connect with one tap to boards you have scanned once.")
                    .font(.subheadline)
                    .foregroundStyle(Tokens.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    model.openScanner()
                } label: {
                    Label("New board with QR", systemImage: "qrcode.viewfinder")
                }
                .buttonStyle(OutlineButtonStyle())
            }
            Button {
                model.path.append(.discover)
            } label: {
                Label("Connect with code (no QR)", systemImage: "number")
            }
            .buttonStyle(OutlineButtonStyle())
        }
    }

    @ViewBuilder private var quickSteps: some View {
        if model.receivers.isEmpty, ended.failure == nil {
            SectionCard(title: "Connect in 3 steps") { StepList(steps: GuideSteps.qr) }
        }
    }

    private var guideLink: some View {
        NavigationCard(title: Text("How do I connect?"), subtitle: Text("Step-by-step guide and common problems"),
                       icon: "questionmark.circle") { model.path.append(.guide) }
    }

    private var version: some View {
        Text("Version \(model.version)")
            .font(.caption)
            .foregroundStyle(Tokens.onSurfaceVariant)
    }

    var body: some View {
        if wide {
            HStack(alignment: .top, spacing: Tokens.space5 + Tokens.space2) {
                VStack(spacing: Tokens.space5) {
                    hero
                    guideLink
                }
                .frame(maxWidth: .infinity)
                VStack(spacing: Tokens.space4) {
                    choices
                    quickSteps
                    version
                }
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: 1120)
        } else {
            VStack(spacing: Tokens.space4) {
                // With an error, the way out comes first.
                if ended.failure == nil { hero }
                choices
                quickSteps
                guideLink
                version
            }
            .frame(maxWidth: Tokens.contentWidth)
        }
    }
}

private struct EndedCard: View {
    let failure: MirrorFailure?
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            Text(failure?.title ?? String(localized: "Mirroring stopped."))
                .font(.title3.weight(.semibold))
            if let body = failure?.body {
                Text(body).font(.body)
            }
            // When a school network isolates clients, the hotspot is the dependable way.
            if failure == .unreachable {
                Text("If the school network blocks it: use the tablet's hotspot")
                    .font(.headline)
                StepList(steps: GuideSteps.hotspot)
                Button("How do I connect?") { model.path.append(.guide) }
                    .frame(minHeight: Tokens.targetSmall)
            }
        }
        .foregroundStyle(failure == nil ? Tokens.onSurface : Tokens.onErrorContainer)
        .padding(Tokens.space4 + Tokens.space1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(failure == nil ? Tokens.surface : Tokens.errorContainer, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .accessibilityElement(children: .combine)
    }
}

/// While connecting and mirroring.
private struct SessionView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: Tokens.space4) {
            switch model.phase {
            case .pairing(let name):
                Progress(text: Text("Connecting to \(name)…"))
                cancel
            case .pairingApproval(let name):
                Progress(text: Text("Waiting for approval on \(name)…"))
                cancel
            case .ready(let receiver):
                ReadyCard(receiver: receiver)
            case .broadcast(let status):
                broadcast(status)
            case .idle, .ended:
                EmptyView()
            }
        }
    }

    private var cancel: some View {
        Button("Cancel") { model.dismiss() }
            .buttonStyle(OutlineButtonStyle())
    }

    @ViewBuilder private func broadcast(_ status: BroadcastStatus) -> some View {
        switch status.phase {
        case .connecting:
            Progress(text: Text("Connecting to \(status.receiverName)…"))
            stopButton
        case .waitingApproval:
            Progress(text: Text("Waiting for approval on \(status.receiverName)…"))
            stopButton
        case .starting:
            Progress(text: Text("Starting…"))
            stopButton
        default:
            MirroringCard(status: status)
            if status.paused {
                Button {
                    model.unpause()
                } label: {
                    Label("Resume", systemImage: "play.fill")
                }
                .buttonStyle(FilledButtonStyle())
            } else {
                Button {
                    model.pause()
                } label: {
                    Label("Pause", systemImage: "pause.fill")
                }
                .buttonStyle(OutlineButtonStyle())
            }
            stopButton
        }
    }

    private var stopButton: some View {
        Button {
            model.stop()
        } label: {
            Label("Stop mirroring", systemImage: "stop.fill")
        }
        .buttonStyle(FilledButtonStyle(destructive: true))
    }
}

private struct Progress: View {
    let text: Text
    var hint: Text?

    var body: some View {
        VStack(spacing: Tokens.space4) {
            ProgressView().controlSize(.large)
            text
                .font(.title3)
                .multilineTextAlignment(.center)
                .foregroundStyle(Tokens.onSurface)
            hint?
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(Tokens.onSurfaceVariant)
        }
        .padding(Tokens.space5 + Tokens.space2)
        .frame(maxWidth: .infinity)
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .accessibilityElement(children: .combine)
    }
}

/// Paired, and one tap from the system's prompt.
private struct ReadyCard: View {
    let receiver: RememberedReceiver
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: Tokens.space4) {
            VStack(spacing: Tokens.space3) {
                Image(systemName: "tv")
                    .font(.largeTitle)
                    .foregroundStyle(Tokens.primary)
                    .accessibilityHidden(true)
                Text(verbatim: receiver.name)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
                Text("Tap the button, then choose Start Broadcast in the system window.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Tokens.onSurfaceVariant)
            }
            .padding(Tokens.space5)
            .frame(maxWidth: .infinity)
            .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))

            ZStack {
                Label("Start mirroring", systemImage: "play.fill")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Tokens.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: Tokens.targetLarge)
                    .background(Tokens.primary, in: RoundedRectangle(cornerRadius: Tokens.radius))
                BroadcastPicker()
                    .frame(maxWidth: .infinity, minHeight: Tokens.targetLarge)
                    .accessibilityLabel(Text("Start mirroring"))
            }
            Button("Cancel") { model.dismiss() }
                .buttonStyle(OutlineButtonStyle())
        }
    }
}

private struct MirroringCard: View {
    let status: BroadcastStatus

    private var audioText: LocalizedStringKey {
        switch status.audio {
        case .on: return "Sound is playing on the board."
        case .offSetting: return "Sound is off (Settings)."
        case .offReceiver: return "Sound is off on the board; it plays on the tablet."
        case .unsupported: return "Sound cannot be shared between this tablet and board."
        case .failed: return "Sound could not be shared; video continues."
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            HStack(spacing: Tokens.space3) {
                Image(systemName: "tv")
                    .font(.title2)
                    .foregroundStyle(Tokens.primary)
                    .accessibilityHidden(true)
                Text("Mirroring to \(status.receiverName)")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
            }
            if status.reconnecting {
                HStack(spacing: Tokens.space3) {
                    ProgressView()
                    Text("Reconnecting…")
                        .font(.body)
                        .foregroundStyle(Tokens.secondary)
                }
            } else if status.paused {
                Text("Paused. The board keeps the last picture; your screen is not shared until you resume.")
                    .font(.body)
                    .foregroundStyle(Tokens.secondary)
            } else if let stats = status.stats {
                QualityLabel(stats: stats)
                StatGrid(stats: stats)
            }
            if !status.reconnecting {
                Text(audioText)
                    .font(.subheadline)
                    .foregroundStyle(status.audio == .on ? Tokens.onSurfaceVariant : Tokens.secondary)
            }
            Text("You can stop from the status bar or this screen.")
                .font(.subheadline)
                .foregroundStyle(Tokens.onSurfaceVariant)
        }
        .padding(Tokens.space4 + Tokens.space1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
    }
}

/// By capture-to-screen delay: the target is 80 ms, 120 ms is acceptable.
private struct QualityLabel: View {
    let stats: BroadcastStats

    var body: some View {
        if let latency = stats.latencyMs {
            let (label, color): (LocalizedStringKey, Color) = {
                if stats.autoLowered { return ("Weak network: quality lowered automatically", Tokens.secondary) }
                if latency <= 120 { return ("Connection: good", Tokens.tertiary) }
                if latency <= 250 { return ("Connection: fair", Tokens.secondary) }
                return ("Connection: poor", Tokens.error)
            }()
            Text(label)
                .font(.headline)
                .foregroundStyle(color)
        }
    }
}

private struct StatGrid: View {
    let stats: BroadcastStats

    private func tile(_ label: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: Tokens.space1) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Tokens.onSurfaceVariant)
            Text(verbatim: value)
                .font(.headline.monospacedDigit())
                .foregroundStyle(Tokens.onSurface)
        }
        .padding(Tokens.space3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surfaceContainer, in: RoundedRectangle(cornerRadius: Tokens.space3))
        .accessibilityElement(children: .combine)
    }

    var body: some View {
        VStack(spacing: Tokens.space2) {
            HStack(spacing: Tokens.space2) {
                tile("Latency", "\(stats.latencyMs.map(String.init) ?? "–") ms")
                tile("Frames/s", "\(Int(stats.fps))")
            }
            HStack(spacing: Tokens.space2) {
                tile("Data rate", String(format: "%.1f Mbps", Double(stats.bitrateBps) / 1_000_000))
                tile("Resolution", "\(stats.width)×\(stats.height)")
            }
        }
    }
}
