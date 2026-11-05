import CastHopReceiver
import SwiftUI

@main
struct ReceiverApp: App {
    @StateObject private var model = ReceiverModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
#if os(macOS)
        Window("CastHop", id: "main") { root }
            .defaultSize(width: 1280, height: 720)
#else
        WindowGroup { root }
#endif
    }

    private var root: some View {
        RootView()
            .environmentObject(model)
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { model.becameActive() }
            }
    }
}

/// Picks the screen for the session state and applies the theme at the size of the window.
struct RootView: View {
    @EnvironmentObject private var model: ReceiverModel

    private var showsVideo: Bool {
        switch model.snapshot.state {
        case .streaming, .paused, .reconnecting: return true
        default: return false
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let theme = Theme(dark: model.darkTheme, scale: Theme.scale(for: geometry.size))
            ZStack {
                if showsVideo {
                    StreamingView()
                } else {
                    WaitingView()
                }
                if let sender = model.snapshot.pendingSenderName {
                    ApprovalView(sender: sender, current: model.snapshot.senderName)
                }
            }
            .environment(\.theme, theme)
            .sheet(isPresented: $model.showingSettings) {
                SettingsView()
                    .environment(\.theme, theme)
                    .environmentObject(model)
            }
        }
        .preferredColorScheme(model.darkTheme ? .dark : .light)
    }
}

/// A sender wants to replace the one on screen.
struct ApprovalView: View {
    let sender: String
    let current: String?
    @EnvironmentObject private var model: ReceiverModel
    @Environment(\.theme) private var theme
    @FocusState private var allowFocused: Bool

    var body: some View {
        ZStack {
            theme.scrim.ignoresSafeArea()
            VStack(spacing: theme.space3) {
                Text("\(sender) wants to share its screen")
                    .font(.system(size: theme.fontTitle, weight: .bold))
                    .foregroundStyle(theme.text)
                    .multilineTextAlignment(.center)
                if let current {
                    Text("The current screen (\(current)) will be replaced.")
                        .font(.system(size: theme.fontBody))
                        .foregroundStyle(theme.textMuted)
                        .multilineTextAlignment(.center)
                }
                HStack(spacing: theme.space3) {
                    Button("Deny") { model.deny() }
                        .buttonStyle(BoardButtonStyle())
                    Button("Allow") { model.approve() }
                        .buttonStyle(BoardButtonStyle(kind: .primary))
                        .focused($allowFocused)
                }
            }
            .padding(theme.space4)
            .frame(maxWidth: 1100 * theme.scale)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
            .padding(theme.space4)
        }
        .onAppear { allowFocused = true }
    }
}
