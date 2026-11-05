import SwiftUI
import UIKit

/// Design tokens, the same values as `android/app/.../ui/theme`. Colours follow the system's light or dark mode.
enum Tokens {
    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        func color(_ hex: UInt32) -> UIColor {
            UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        }
        return Color(UIColor { $0.userInterfaceStyle == .dark ? color(dark) : color(light) })
    }

    static let primary = dynamic(0x0B5CAD, 0xA7C8FF)
    static let onPrimary = dynamic(0xFFFFFF, 0x003063)
    static let secondary = dynamic(0x8A5100, 0xFFB86E)
    static let tertiary = dynamic(0x006A60, 0x82D5C7)
    static let error = dynamic(0xBA1A1A, 0xFFB4AB)
    static let onError = dynamic(0xFFFFFF, 0x690005)
    static let errorContainer = dynamic(0xFFDAD6, 0x93000A)
    static let onErrorContainer = dynamic(0x410002, 0xFFDAD6)
    static let background = dynamic(0xF6F8FC, 0x101418)
    static let surface = dynamic(0xFFFFFF, 0x1C2026)
    static let surfaceContainer = dynamic(0xEAEEF4, 0x262A31)
    static let onSurface = dynamic(0x181C22, 0xE0E2E9)
    static let onSurfaceVariant = dynamic(0x434852, 0xC3C7D1)
    static let outline = dynamic(0xC3C7D1, 0x434852)

    static let space1: CGFloat = 4
    static let space2: CGFloat = 8
    static let space3: CGFloat = 12
    static let space4: CGFloat = 16
    static let space5: CGFloat = 24
    static let radius: CGFloat = 16
    /// Primary actions.
    static let targetLarge: CGFloat = 72
    static let targetMedium: CGFloat = 64
    static let targetSmall: CGFloat = 48
    /// Widest column on a large screen.
    static let contentWidth: CGFloat = 560
    static let wideLayout: CGFloat = 760
}

struct FilledButtonStyle: ButtonStyle {
    var destructive = false
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.title3.weight(.semibold))
            .foregroundStyle(destructive ? Tokens.onError : Tokens.onPrimary)
            .frame(maxWidth: .infinity, minHeight: Tokens.targetLarge)
            .background(destructive ? Tokens.error : Tokens.primary, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .opacity(!enabled ? 0.4 : configuration.isPressed ? 0.8 : 1)
    }
}

struct OutlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Tokens.primary)
            .frame(maxWidth: .infinity, minHeight: Tokens.targetMedium)
            .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outline, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: Tokens.radius))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A titled card.
struct SectionCard<Content: View>: View {
    let title: LocalizedStringKey
    var icon: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            HStack(spacing: Tokens.space3) {
                if let icon {
                    Image(systemName: icon)
                        .foregroundStyle(Tokens.primary)
                        .accessibilityHidden(true)
                }
                Text(title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Tokens.onSurface)
                    .accessibilityAddTraits(.isHeader)
            }
            content
        }
        .padding(Tokens.space4 + Tokens.space1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
    }
}

/// Numbered steps.
struct StepList: View {
    let steps: [LocalizedStringKey]

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            ForEach(steps.indices, id: \.self) { index in
                HStack(alignment: .firstTextBaseline, spacing: Tokens.space3) {
                    Text(verbatim: "\(index + 1).")
                        .font(.body.weight(.semibold).monospacedDigit())
                        .foregroundStyle(Tokens.primary)
                    Text(steps[index])
                        .font(.body)
                        .foregroundStyle(Tokens.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

struct CheckList: View {
    let items: [LocalizedStringKey]

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            ForEach(items.indices, id: \.self) { index in
                HStack(alignment: .firstTextBaseline, spacing: Tokens.space3) {
                    Image(systemName: "checkmark.circle")
                        .foregroundStyle(Tokens.tertiary)
                        .accessibilityHidden(true)
                    Text(items[index])
                        .font(.body)
                        .foregroundStyle(Tokens.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// A card that goes somewhere.
struct NavigationCard: View {
    let title: Text
    var subtitle: Text?
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Tokens.space4) {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundStyle(Tokens.primary)
                    .frame(width: 44, height: 44)
                    .background(Tokens.surfaceContainer, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: Tokens.space1) {
                    title
                        .font(.headline)
                        .foregroundStyle(Tokens.onSurface)
                    subtitle?
                        .font(.subheadline)
                        .foregroundStyle(Tokens.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .foregroundStyle(Tokens.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(Tokens.space3)
            .frame(minHeight: Tokens.targetLarge)
            .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .contentShape(RoundedRectangle(cornerRadius: Tokens.radius))
        }
        .buttonStyle(.plain)
    }
}

/// A scrolling page in a readable column, as the sub screens have it.
struct SubScreen<Content: View>: View {
    let title: LocalizedStringKey
    var maxWidth = Tokens.contentWidth
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.space4) { content }
                .frame(maxWidth: maxWidth)
                .frame(maxWidth: .infinity)
                .padding(Tokens.space5)
        }
        .background(Tokens.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
