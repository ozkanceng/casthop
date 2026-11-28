import CastHopSender
import ReplayKit
import SwiftUI

/// The system's start button. Only it may open the broadcast sheet. It covers the styled label, which stays visible.
private struct BroadcastPicker: UIViewRepresentable {
    static let extensionId = "com.sorumatik.casthop.broadcast"

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
    @State private var renaming: RememberedReceiver?

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
                        ConnectView(wide: geometry.size.width >= Tokens.wideLayout) { renaming = $0 }
                    } else {
                        SessionView().frame(maxWidth: Tokens.contentWidth)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(Tokens.space5)
            }
        }
        .background(Tokens.background.ignoresSafeArea())
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
        .sheet(item: $renaming) { receiver in
            RenameBoardSheet(rawName: receiver.name, currentNickname: receiver.nickname) { newName in
                model.rename(receiver, nickname: newName)
            }
        }
        .onAppear {
            if idle && !model.receivers.isEmpty { model.startPassivePresence() }
        }
        .onDisappear {
            model.stopPassivePresence()
        }
        .onChange(of: idle) { _, isIdle in
            if isIdle && !model.receivers.isEmpty {
                model.startPassivePresence()
            } else {
                model.stopPassivePresence()
            }
        }
        .onChange(of: model.receivers.isEmpty) { _, empty in
            if empty {
                model.stopPassivePresence()
            } else if idle {
                model.startPassivePresence()
            }
        }
    }
}

/// With no connection: what went wrong, remembered boards, modern connect choices, quick steps and guide.
private struct ConnectView: View {
    let wide: Bool
    let onRequestRename: (RememberedReceiver) -> Void
    @EnvironmentObject private var model: AppModel

    private var ended: (failure: MirrorFailure?, shown: Bool) {
        if case .ended(let failure) = model.phase { return (failure, true) }
        return (nil, false)
    }

    private var hero: some View {
        VStack(spacing: Tokens.space4) {
            ZStack {
                Circle()
                    .fill(Tokens.primary.opacity(0.12))
                    .frame(width: 92, height: 92)
                Image(systemName: "rectangle.on.rectangle.angled")
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundStyle(Tokens.primary)
            }
            .accessibilityHidden(true)
            VStack(spacing: Tokens.space2) {
                Text("Mirror your tablet screen to the classroom whiteboard, wirelessly.")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Tokens.onSurface)
            }
        }
        .padding(.vertical, Tokens.space5)
        .padding(.horizontal, Tokens.space4)
        .frame(maxWidth: .infinity)
        .background(
            LinearGradient(
                colors: [Tokens.primaryContainer.opacity(0.42), Tokens.surface],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: Tokens.radius + 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: Tokens.radius + 4)
                .stroke(Tokens.outlineVariant.opacity(0.8), lineWidth: 1)
        )
    }

    @ViewBuilder private var choices: some View {
        if ended.shown {
            ConnectionErrorCard(
                failure: ended.failure,
                onRetry: { model.retryLast() },
                onManualIp: {
                    model.dismiss()
                    model.path.append(.manualIp)
                },
                onGuide: { model.path.append(.guide) },
                onDismiss: { model.dismiss() }
            )
            if ended.failure != nil {
                Button("OK") { model.dismiss() }
                    .font(.subheadline.weight(.semibold))
                    .frame(minHeight: Tokens.targetSmall)
            }
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
                HStack {
                    Text("Remembered boards")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(Tokens.onSurface)
                    Spacer()
                }
                let hasOnlineInfo = !model.onlineFingerprints.isEmpty
                ForEach(model.sortedReceivers) { receiver in
                    let online = model.onlineFingerprints.contains(receiver.fingerprint)
                    RememberedBoardCard(
                        receiver: receiver,
                        online: online,
                        dimmed: hasOnlineInfo && !online,
                        onConnect: { model.connect(receiver) },
                        onToggleFavorite: { model.toggleFavorite(receiver) },
                        onRename: { onRequestRename(receiver) },
                        onForget: { model.forget(receiver) }
                    )
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
                .buttonStyle(TonalButtonStyle())
            }

            // Balanced secondary connection paths: Code + Direct IP
            HStack(spacing: Tokens.space3) {
                Button {
                    model.dismiss()
                    model.path.append(.discover)
                } label: {
                    Label("With code", systemImage: "number")
                }
                .buttonStyle(OutlineButtonStyle())

                Button {
                    model.dismiss()
                    model.path.append(.manualIp)
                } label: {
                    Label("With IP", systemImage: "network")
                }
                .buttonStyle(OutlineButtonStyle())
            }
        }
    }

    @ViewBuilder private var quickSteps: some View {
        if model.receivers.isEmpty, ended.failure == nil {
            SectionCard(title: "Connect in 3 steps", icon: "list.number") { StepList(steps: GuideSteps.qr) }
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

private struct RememberedBoardCard: View {
    let receiver: RememberedReceiver
    let online: Bool
    let dimmed: Bool
    let onConnect: () -> Void
    let onToggleFavorite: () -> Void
    let onRename: () -> Void
    let onForget: () -> Void

    private var subtitle: String {
        BoardIdentity.subtitle(hosts: receiver.hosts, fingerprintOrPrefix: receiver.fingerprint,
                               rawName: receiver.name, nickname: receiver.nickname)
    }

    var body: some View {
        VStack(spacing: 0) {
            Button(action: onConnect) {
                HStack(spacing: Tokens.space4) {
                    IconBadge(systemName: boardKindIcon(receiver.kind))
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(verbatim: receiver.displayName)
                                .font(.headline)
                                .foregroundStyle(Tokens.onSurface)
                                .lineLimit(1)
                            if receiver.favorite {
                                Image(systemName: "star.fill")
                                    .font(.caption)
                                    .foregroundStyle(Tokens.secondary)
                            }
                            if online {
                                HStack(spacing: 4) {
                                    Circle()
                                        .fill(Tokens.tertiary)
                                        .frame(width: 7, height: 7)
                                    Text("On this network")
                                        .font(.caption2.weight(.semibold))
                                        .foregroundStyle(Tokens.tertiary)
                                }
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(Tokens.tertiary.opacity(0.14), in: Capsule())
                            }
                        }
                        Text(verbatim: subtitle)
                            .font(.subheadline.monospaced())
                            .foregroundStyle(Tokens.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Tokens.onSurfaceVariant)
                }
                .padding(.horizontal, Tokens.space4)
                .padding(.vertical, Tokens.space3 + 2)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Divider().padding(.horizontal, Tokens.space3)

            HStack {
                Button(action: onToggleFavorite) {
                    Image(systemName: receiver.favorite ? "star.fill" : "star")
                        .foregroundStyle(receiver.favorite ? Tokens.secondary : Tokens.onSurfaceVariant)
                        .frame(width: Tokens.targetSmall, height: 42)
                }
                .accessibilityLabel(Text(receiver.favorite ? "Remove \(receiver.displayName) from favorites" : "Add \(receiver.displayName) to favorites"))

                Button(action: onRename) {
                    Label("Rename", systemImage: "pencil")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Tokens.primary)
                        .frame(minHeight: 42)
                }
                Spacer()
                Button(action: onForget) {
                    Label("Forget", systemImage: "trash")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Tokens.onSurfaceVariant)
                        .frame(minHeight: 42)
                }
                .accessibilityLabel(Text("Forget \(receiver.displayName)"))
            }
            .padding(.horizontal, Tokens.space3)
            .padding(.vertical, 2)
        }
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .overlay(
            RoundedRectangle(cornerRadius: Tokens.radius)
                .stroke(online ? Tokens.tertiary.opacity(0.65) : Tokens.outlineVariant, lineWidth: online ? 1.5 : 1)
        )
        .opacity(dimmed ? 0.78 : 1.0)
    }
}

/// While connecting and mirroring.
private struct SessionView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: Tokens.space4) {
            switch model.phase {
            case .pairing(let name):
                ConnectionStepperCard(boardName: name, activeStep: 1, onCancel: { model.dismiss() })
            case .pairingApproval(let name):
                ConnectionStepperCard(boardName: name, activeStep: 1, waitingApproval: true, onCancel: { model.dismiss() })
            case .ready(let receiver):
                ReadyCard(receiver: receiver)
            case .broadcast(let status):
                broadcast(status)
            case .idle, .ended:
                EmptyView()
            }
        }
    }

    @ViewBuilder private func broadcast(_ status: BroadcastStatus) -> some View {
        switch status.phase {
        case .connecting:
            ConnectionStepperCard(boardName: status.receiverName, activeStep: 0, onCancel: { model.stop() })
        case .waitingApproval:
            ConnectionStepperCard(boardName: status.receiverName, activeStep: 1, waitingApproval: true, onCancel: { model.stop() })
        case .starting:
            ConnectionStepperCard(boardName: status.receiverName, activeStep: 2, onCancel: { model.stop() })
        default:
            if let camera = model.camera {
                CameraPreview(layer: camera.preview)
                    .aspectRatio(Tokens.previewAspect, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Tokens.radius))
                    .accessibilityLabel(Text("Camera preview"))
            }
            MirroringCard(status: status, camera: model.camera != nil)
            if let camera = model.camera, camera.canSwitch {
                Button {
                    model.switchCamera()
                } label: {
                    Label("Switch camera", systemImage: "arrow.triangle.2.circlepath.camera")
                }
                .buttonStyle(OutlineButtonStyle())
            }
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
                .buttonStyle(TonalButtonStyle())
            }
            stopButton
            Button {
                model.path.append(.settings)
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
            .buttonStyle(OutlineButtonStyle())
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

/// Paired, and one tap from the system's prompt.
private struct ReadyCard: View {
    let receiver: RememberedReceiver
    @EnvironmentObject private var model: AppModel

    private var subtitle: String {
        BoardIdentity.subtitle(hosts: receiver.hosts, fingerprintOrPrefix: receiver.fingerprint,
                               rawName: receiver.name, nickname: receiver.nickname)
    }

    var body: some View {
        VStack(spacing: Tokens.space4) {
            VStack(spacing: Tokens.space3) {
                IconBadge(systemName: boardKindIcon(receiver.kind))
                Text(verbatim: receiver.displayName)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
                Text(verbatim: subtitle)
                    .font(.subheadline.monospaced())
                    .foregroundStyle(Tokens.onSurfaceVariant)
                Text("Tap the button, then choose Start Broadcast in the system window.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Tokens.onSurfaceVariant)
            }
            .padding(Tokens.space5)
            .frame(maxWidth: .infinity)
            .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))

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
            Button {
                Task { await model.startCamera() }
            } label: {
                Label("Mirror the camera", systemImage: "camera.fill")
            }
            .buttonStyle(OutlineButtonStyle())
            .accessibilityHint(Text("Sends the camera instead of the screen, for showing books, notebooks and papers."))
            Text("Sends the camera instead of the screen, for showing books, notebooks and papers.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(Tokens.onSurfaceVariant)
            Button("Cancel") { model.dismiss() }
                .buttonStyle(OutlineButtonStyle())
        }
    }
}

private struct MirroringCard: View {
    let status: BroadcastStatus
    let camera: Bool

    private var audioText: LocalizedStringKey {
        switch status.audio {
        case .on: return "Sound is playing on the board."
        case .offSetting: return "Sound is off (Settings)."
        case .offReceiver: return "Sound is off on the board; it plays on the tablet."
        case .unsupported: return "Sound cannot be shared between this tablet and board."
        case .failed: return "Sound could not be shared; video continues."
        case .offCamera: return "Sound is not sent in camera mode."
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            HStack(spacing: Tokens.space3) {
                IconBadge(systemName: "tv")
                Text("Mirroring to \(status.receiverName)")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
                    .lineLimit(2)
                Spacer(minLength: 4)
                if !status.reconnecting && !status.paused {
                    LivePillBadge()
                }
            }
            if status.reconnecting {
                HStack(spacing: Tokens.space3) {
                    ProgressView()
                    Text("Reconnecting…")
                        .font(.body)
                        .foregroundStyle(Tokens.secondary)
                }
            } else if status.paused {
                Text(camera ? "Paused. The board keeps the last picture; the camera is not shared until you resume."
                            : "Paused. The board keeps the last picture; your screen is not shared until you resume.")
                    .font(.body)
                    .foregroundStyle(Tokens.secondary)
            } else if let stats = status.stats {
                QualityBadgeAndAlerts(stats: stats)
                StatGrid(stats: stats)
            }
            if !status.reconnecting {
                Text(audioText)
                    .font(.subheadline)
                    .foregroundStyle(status.audio == .on ? Tokens.onSurfaceVariant : Tokens.secondary)
            }
            Text(camera ? "Keep CastHop open: the camera stops if you leave the app." : "You can stop from the status bar or this screen.")
                .font(.subheadline)
                .foregroundStyle(Tokens.onSurfaceVariant)
        }
        .padding(Tokens.space4 + Tokens.space1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
    }
}

/// Live connection quality indicator & weak-link banner (renders even before first latency sample arrives).
private struct QualityBadgeAndAlerts: View {
    let stats: BroadcastStats

    private var labelAndColor: (LocalizedStringKey, Color) {
        if stats.autoLowered { return ("Weak network: quality lowered automatically", Tokens.secondary) }
        switch stats.quality {
        case .poor: return ("Connection: poor", Tokens.error)
        case .fair: return ("Connection: fair", Tokens.secondary)
        case .good: return ("Connection: good", Tokens.tertiary)
        }
    }

    var body: some View {
        let (label, color) = labelAndColor
        Text(label)
            .font(.headline)
            .foregroundStyle(color)

        if stats.autoLowered || stats.quality == .poor {
            HStack(alignment: .top, spacing: Tokens.space2) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.subheadline)
                Text("Weak connection — video quality was lowered automatically to prevent freezes.")
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(stats.quality == .poor ? Tokens.onErrorContainer : Tokens.onSecondaryContainer)
            .padding(Tokens.space3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                stats.quality == .poor ? Tokens.errorContainer : Tokens.secondaryContainer,
                in: RoundedRectangle(cornerRadius: Tokens.radiusSmall)
            )
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
        .background(Tokens.surfaceContainer, in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
        .accessibilityElement(children: .combine)
    }

    var body: some View {
        VStack(spacing: Tokens.space2) {
            HStack(spacing: Tokens.space2) {
                tile("Latency", "\(stats.latencyMs.map(String.init) ?? "–") ms")
                tile("Ping", "\(stats.rttMs.map(String.init) ?? "–") ms")
            }
            HStack(spacing: Tokens.space2) {
                tile("Loss", String(format: "%.1f%%", stats.lossPercent))
                tile("Data rate", String(format: "%.1f Mbps", Double(stats.bitrateBps) / 1_000_000))
            }
            tile("Resolution", "\(stats.width)×\(stats.height)")
        }
    }
}
