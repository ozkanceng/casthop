import AVFoundation
import CastHopReceiver
import SwiftUI

#if os(macOS)
/// Hosts the display layer, turned when the picture is (`DisplayOutput.place`). The layer stays the same across layout
/// changes, so frames are not interrupted.
private final class LayerView: NSView {
    private let display: DisplayOutput

    init(_ display: DisplayOutput) {
        self.display = display
        super.init(frame: .zero)
        wantsLayer = true
        self.layer?.backgroundColor = NSColor.black.cgColor
        self.layer?.addSublayer(display.layer)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layout() {
        super.layout()
        display.place(in: bounds)
    }
}

private struct VideoSurface: NSViewRepresentable {
    let display: DisplayOutput
    let gravity: AVLayerVideoGravity

    func makeNSView(context: Context) -> LayerView { LayerView(display) }
    func updateNSView(_ view: LayerView, context: Context) { display.layer.videoGravity = gravity }
}
#else
private final class LayerView: UIView {
    private let display: DisplayOutput

    init(_ display: DisplayOutput) {
        self.display = display
        super.init(frame: .zero)
        backgroundColor = .black
        self.layer.addSublayer(display.layer)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        display.place(in: bounds)
    }
}

private struct VideoSurface: UIViewRepresentable {
    let display: DisplayOutput
    let gravity: AVLayerVideoGravity

    func makeUIView(context: Context) -> LayerView { LayerView(display) }
    func updateUIView(_ view: LayerView, context: Context) { display.layer.videoGravity = gravity }
}
#endif

/// The mirrored screen. Controls stay out of the way until someone reaches for them.
struct StreamingView: View {
    private static let controlsSeconds = 5.0

    @EnvironmentObject private var model: ReceiverModel
    @Environment(\.theme) private var theme
    @State private var controlsVisible = false
    @State private var hideTask: Task<Void, Never>?
    @FocusState private var stopFocused: Bool

    private var snapshot: ReceiverSnapshot { model.snapshot }

    private var banner: Text? {
        switch snapshot.state {
        case .paused: return Text("Mirroring paused")
        case .reconnecting: return Text("Connection lost, reconnecting…")
        default: return nil
        }
    }

    private func showControls() {
        controlsVisible = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(Self.controlsSeconds))
            if !Task.isCancelled { controlsVisible = false }
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(display: model.display, gravity: model.pictureMode == .fit ? .resizeAspect : .resizeAspectFill)
                .ignoresSafeArea()
                .accessibilityHidden(true)
            VStack {
                if let banner {
                    banner
                        .font(.system(size: theme.fontBody, weight: .semibold))
                        .foregroundStyle(theme.text)
                        .padding(theme.space2)
                        .background(theme.scrim, in: RoundedRectangle(cornerRadius: theme.radius))
                        .padding(.top, theme.space3)
                }
                Spacer()
                if controlsVisible { controls }
            }
        }
#if os(tvOS)
        // The remote has no pointer: any press brings the controls up, and Menu dismisses them.
        .focusable(!controlsVisible)
        .onPlayPauseCommand(perform: showControls)
        .onMoveCommand { _ in showControls() }
        .onExitCommand {
            if controlsVisible {
                controlsVisible = false
            } else {
                showControls()
            }
        }
#endif
        .contentShape(Rectangle())
        .onTapGesture(perform: showControls)
#if os(macOS)
        .onContinuousHover { phase in
            if case .active = phase { showControls() }
        }
#endif
        .onAppear(perform: showControls)
    }

    private var statsLine: String {
        let s = snapshot.stats
        let latency = s.latencyMs.map(String.init) ?? "–"
        return "\(latency) ms · \(Int(s.fps.rounded())) fps · \(String(format: "%.1f", Double(s.bitrateKbps) / 1000)) Mbps"
    }

    private var controls: some View {
        HStack(spacing: theme.space3) {
            VStack(alignment: .leading, spacing: theme.space1) {
                if let sender = snapshot.senderName {
                    Text(verbatim: sender)
                        .font(.system(size: theme.fontLabel, weight: .semibold))
                        .foregroundStyle(theme.text)
                        .lineLimit(1)
                }
                Text(verbatim: statsLine)
                    .font(.system(size: theme.fontLabel).monospacedDigit())
                    .foregroundStyle(theme.textMuted)
            }
            Spacer()
            Button("Settings") { model.showingSettings = true }
                .buttonStyle(BoardButtonStyle())
            Button("Stop mirroring") { model.stopSession() }
                .buttonStyle(BoardButtonStyle(kind: .danger))
                .focused($stopFocused)
        }
        .padding(theme.space3)
        .background(theme.scrim, in: RoundedRectangle(cornerRadius: theme.radius))
        .padding(theme.space3)
        .defaultFocus($stopFocused, true)
        .task {
            // The focus system sees the button one pass after it appears, so an assignment in onAppear is lost.
            try? await Task.sleep(for: .milliseconds(100))
            stopFocused = true
        }
    }
}
