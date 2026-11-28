import CastHopProtocol
import SwiftUI
#if os(macOS)
import AppKit
#endif

/// Sleek, non-blocking update pill placed next to the receiver status badge in `WaitingView`.
struct UpdatePill: View {
    let version: String
    let onTap: () -> Void
    let onDismiss: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: theme.space1) {
            Button(action: onTap) {
                HStack(spacing: theme.space1) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: max(14, theme.fontCaption), weight: .bold))
                        .foregroundStyle(theme.primary)
                    Text("New update: v\(version)")
                        .font(.system(size: theme.fontCaption, weight: .semibold))
                        .foregroundStyle(theme.text)
                    Text("Details ›")
                        .font(.system(size: theme.fontCaption, weight: .bold))
                        .foregroundStyle(theme.primary)
                }
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: max(12, theme.fontCaption * 0.75), weight: .bold))
                    .foregroundStyle(theme.textMuted)
                    .padding(4)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Dismiss"))
        }
        .padding(.horizontal, theme.space2)
        .padding(.vertical, theme.space1)
        .background(theme.primarySubtle, in: Capsule())
        .overlay(Capsule().stroke(theme.primary, lineWidth: 1.5))
    }
}

/// Modal update sheet matching `Theme.swift` tokens for macOS, iPadOS, and tvOS receivers.
struct UpdateSheet: View {
    let release: UpdateRelease
    let onSkip: () -> Void
    let onLater: () -> Void
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space3) {
                HStack(spacing: theme.space3) {
                    ZStack {
                        Circle()
                            .fill(theme.primarySubtle)
                            .frame(width: max(48, 64 * theme.scale), height: max(48, 64 * theme.scale))
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: max(26, 34 * theme.scale), weight: .bold))
                            .foregroundStyle(theme.primary)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text("New CastHop update available")
                            .font(.system(size: max(theme.fontTitle * 0.8, 24), weight: .bold))
                            .foregroundStyle(theme.text)
                        Text(verbatim: "v\(release.currentVersion)  →  v\(release.latestVersion)")
                            .font(.system(size: max(theme.fontCaption, 15), weight: .bold, design: .monospaced))
                            .foregroundStyle(theme.primary)
                            .padding(.horizontal, theme.space2)
                            .padding(.vertical, 4)
                            .background(theme.primarySubtle, in: Capsule())
                            .overlay(Capsule().stroke(theme.primary, lineWidth: 1))
                    }
                }

                if !release.notes.isEmpty {
                    VStack(alignment: .leading, spacing: theme.space1) {
                        Text("What's new")
                            .font(.system(size: max(theme.fontCaption, 15), weight: .bold))
                            .foregroundStyle(theme.primary)
                        Text(release.notes)
                            .font(.system(size: max(theme.fontBody * 0.85, 18)))
                            .foregroundStyle(theme.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(theme.space3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(theme.surfaceRaised, in: RoundedRectangle(cornerRadius: theme.radius))
                    .overlay(RoundedRectangle(cornerRadius: theme.radius).stroke(theme.border, lineWidth: 1))
                }

#if os(tvOS)
                if let image = QrImage.make(release.url.absoluteString) {
                    HStack(spacing: theme.space3) {
                        Image(decorative: image, scale: 1)
                            .interpolation(.none)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 180, height: 180)
                            .padding(12)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: theme.radius))
                        Text("Scan with your phone to open the download page.")
                            .font(.system(size: max(theme.fontLabel, 18)))
                            .foregroundStyle(theme.textMuted)
                    }
                }
#else
                Button("Download update") {
#if os(macOS)
                    NSWorkspace.shared.open(release.url)
#else
                    UIApplication.shared.open(release.url)
#endif
                    dismiss()
                }
                .buttonStyle(BoardButtonStyle(kind: .primary))
#endif

                HStack(spacing: theme.space2) {
                    Button("Skip this version") {
                        onSkip()
                        dismiss()
                    }
                    .buttonStyle(BoardButtonStyle())

                    Spacer()

                    Button("Remind me later") {
                        onLater()
                        dismiss()
                    }
                    .buttonStyle(BoardButtonStyle())
                }
            }
            .padding(theme.space4)
        }
        .background(theme.background)
        .frame(minWidth: 580, minHeight: 420)
    }
}
