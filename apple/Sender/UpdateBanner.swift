import CastHopProtocol
import SwiftUI

/// Modern SwiftUI update banner shown quietly at the top of the home screen when a newer App Store release is live.
struct UpdateBanner: View {
    let release: UpdateRelease
    let onOpenDetails: () -> Void
    let onUpdateNow: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            HStack(alignment: .top, spacing: Tokens.space3) {
                ZStack {
                    Circle()
                        .fill(Tokens.primary)
                        .frame(width: 40, height: 40)
                    Image(systemName: "arrow.down.app.fill")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Tokens.onPrimary)
                }
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: Tokens.space2) {
                        Text("New update available")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Tokens.onSurface)
                            .lineLimit(1)
                        Text(verbatim: "v\(release.latestVersion)")
                            .font(.caption.weight(.bold).monospaced())
                            .foregroundStyle(Tokens.onPrimaryContainer)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Tokens.primaryContainer, in: Capsule())
                    }
                    if !release.notes.isEmpty {
                        Text(release.notes)
                            .font(.caption)
                            .foregroundStyle(Tokens.onSurfaceVariant)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Tokens.onSurfaceVariant)
                        .frame(width: 28, height: 28)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Dismiss update banner"))
            }

            HStack(spacing: Tokens.space2) {
                Button(action: onUpdateNow) {
                    Text("Update on App Store")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Tokens.onPrimary)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 38)
                        .background(Tokens.primary, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)

                Button(action: onOpenDetails) {
                    Text("Details")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Tokens.primary)
                        .padding(.horizontal, 10)
                        .frame(minHeight: 38)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(Tokens.space4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Tokens.primaryContainer.opacity(0.45), Tokens.surface],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: Tokens.radius + 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: Tokens.radius + 4)
                .stroke(Tokens.primary.opacity(0.4), lineWidth: 1.2)
        )
        .contentShape(RoundedRectangle(cornerRadius: Tokens.radius + 4))
        .onTapGesture(perform: onOpenDetails)
    }
}

/// Detail sheet showing the version transition, release notes, and skip/remind actions.
struct UpdateDetailSheet: View {
    let release: UpdateRelease
    let onUpdateNow: () -> Void
    let onSkipVersion: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Tokens.space4) {
                    HStack(spacing: Tokens.space3) {
                        ZStack {
                            Circle()
                                .fill(Tokens.primaryContainer)
                                .frame(width: 54, height: 54)
                            Image(systemName: "sparkles")
                                .font(.title2.weight(.semibold))
                                .foregroundStyle(Tokens.primary)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text("CastHop update available")
                                .font(.title3.weight(.bold))
                                .foregroundStyle(Tokens.onSurface)
                            Text(verbatim: "v\(release.currentVersion)  →  v\(release.latestVersion)")
                                .font(.caption.weight(.semibold).monospaced())
                                .foregroundStyle(Tokens.onPrimaryContainer)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Tokens.primaryContainer.opacity(0.8), in: Capsule())
                        }
                    }

                    if !release.notes.isEmpty {
                        VStack(alignment: .leading, spacing: Tokens.space2) {
                            Text("What's new")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Tokens.primary)
                            Text(release.notes)
                                .font(.body)
                                .foregroundStyle(Tokens.onSurface)
                        }
                        .padding(Tokens.space4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Tokens.surfaceContainer, in: RoundedRectangle(cornerRadius: Tokens.radius))
                    }

                    Button {
                        onUpdateNow()
                        dismiss()
                    } label: {
                        Label("Update on App Store", systemImage: "arrow.down.app.fill")
                            .font(.headline)
                            .foregroundStyle(Tokens.onPrimary)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(Tokens.primary, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)

                    HStack {
                        Button("Skip this version") {
                            onSkipVersion()
                            dismiss()
                        }
                        .foregroundStyle(Tokens.onSurfaceVariant)
                        Spacer()
                        Button("Remind me later") { dismiss() }
                            .foregroundStyle(Tokens.primary)
                    }
                    .font(.subheadline.weight(.medium))
                    .padding(.top, Tokens.space1)
                }
                .padding(Tokens.space5)
            }
            .background(Tokens.background.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
