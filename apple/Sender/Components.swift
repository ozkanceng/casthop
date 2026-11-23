import SwiftUI
import UIKit

/// Design tokens, aligned with `android/app/.../ui/theme` and `receiver/src/qml/Theme.qml`.
/// Colours follow the system's light or dark mode and keep WCAG AA contrast (≥ 4.5:1).
enum Tokens {
    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        func color(_ hex: UInt32) -> UIColor {
            UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        }
        return Color(UIColor { $0.userInterfaceStyle == .dark ? color(dark) : color(light) })
    }

    static let primary = dynamic(0x0B5CAD, 0xA7C8FF)
    static let onPrimary = dynamic(0xFFFFFF, 0x003063)
    static let primaryContainer = dynamic(0xD5E3FF, 0x00468B)
    static let onPrimaryContainer = dynamic(0x001B3D, 0xD5E3FF)
    static let secondary = dynamic(0x8A5100, 0xFFB86E)
    static let secondaryContainer = dynamic(0xFFDDB8, 0x693C00)
    static let onSecondaryContainer = dynamic(0x2C1600, 0xFFDDB8)
    static let tertiary = dynamic(0x006A60, 0x82D5C7)
    static let tertiaryContainer = dynamic(0x9EF2E3, 0x005048)
    static let onTertiaryContainer = dynamic(0x00201C, 0x9EF2E3)
    static let error = dynamic(0xBA1A1A, 0xFFB4AB)
    static let onError = dynamic(0xFFFFFF, 0x690005)
    static let errorContainer = dynamic(0xFFDAD6, 0x93000A)
    static let onErrorContainer = dynamic(0x410002, 0xFFDAD6)
    static let background = dynamic(0xF6F8FC, 0x101418)
    static let surface = dynamic(0xFFFFFF, 0x1A1F26)
    static let surfaceLow = dynamic(0xF0F3F8, 0x151A20)
    static let surfaceContainer = dynamic(0xEAEEF4, 0x242A32)
    static let onSurface = dynamic(0x181C22, 0xE2E5EC)
    static let onSurfaceVariant = dynamic(0x434852, 0xBFC5D0)
    static let outline = dynamic(0x737883, 0x8D919A)
    static let outlineVariant = dynamic(0xD0D5DE, 0x383E48)

    static let space1: CGFloat = 4
    static let space2: CGFloat = 8
    static let space3: CGFloat = 12
    static let space4: CGFloat = 16
    static let space5: CGFloat = 24
    static let radius: CGFloat = 18
    static let radiusSmall: CGFloat = 12
    /// Primary actions.
    static let targetLarge: CGFloat = 68
    static let previewAspect: CGFloat = 16 / 9
    static let targetMedium: CGFloat = 56
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
            .padding(.horizontal, Tokens.space4)
            .frame(maxWidth: .infinity, minHeight: Tokens.targetLarge)
            .background(destructive ? Tokens.error : Tokens.primary, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(!enabled ? 0.42 : configuration.isPressed ? 0.88 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct TonalButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Tokens.onPrimaryContainer)
            .padding(.horizontal, Tokens.space4)
            .frame(maxWidth: .infinity, minHeight: Tokens.targetMedium)
            .background(Tokens.primaryContainer, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(!enabled ? 0.45 : configuration.isPressed ? 0.82 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct OutlineButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Tokens.primary)
            .padding(.horizontal, Tokens.space3)
            .frame(maxWidth: .infinity, minHeight: Tokens.targetMedium)
            .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1.25))
            .contentShape(RoundedRectangle(cornerRadius: Tokens.radius))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(!enabled ? 0.45 : configuration.isPressed ? 0.78 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Circular soft icon badge shared across cards.
struct IconBadge: View {
    let systemName: String
    var tint: Color = Tokens.onPrimaryContainer
    var background: Color = Tokens.primaryContainer

    var body: some View {
        Image(systemName: systemName)
            .font(.title3.weight(.semibold))
            .foregroundStyle(tint)
            .frame(width: 42, height: 42)
            .background(background, in: Circle())
            .accessibilityHidden(true)
    }
}

/// A titled card with subtle surface border.
struct SectionCard<Content: View>: View {
    let title: LocalizedStringKey
    var icon: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            HStack(spacing: Tokens.space3) {
                if let icon {
                    IconBadge(systemName: icon)
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
        .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
    }
}

/// Numbered steps with circular step markers.
struct StepList: View {
    let steps: [LocalizedStringKey]

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            ForEach(steps.indices, id: \.self) { index in
                HStack(alignment: .top, spacing: Tokens.space3) {
                    Text(verbatim: "\(index + 1)")
                        .font(.subheadline.weight(.bold).monospacedDigit())
                        .foregroundStyle(Tokens.onPrimary)
                        .frame(width: 26, height: 26)
                        .background(Tokens.primary, in: Circle())
                    Text(steps[index])
                        .font(.body)
                        .foregroundStyle(Tokens.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
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
                    Image(systemName: "checkmark.circle.fill")
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

/// A tappable navigation card with optional subtitle and trailing badge.
struct NavigationCard: View {
    let title: Text
    var subtitle: Text?
    let icon: String
    var badgeSystemName: String?
    var badgeColor: Color = Tokens.secondary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Tokens.space4) {
                IconBadge(systemName: icon)
                VStack(alignment: .leading, spacing: Tokens.space1) {
                    title
                        .font(.headline)
                        .foregroundStyle(Tokens.onSurface)
                        .lineLimit(2)
                    subtitle?
                        .font(.subheadline.monospaced())
                        .foregroundStyle(Tokens.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if let badgeSystemName {
                    Image(systemName: badgeSystemName)
                        .foregroundStyle(badgeColor)
                        .accessibilityHidden(true)
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Tokens.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Tokens.space4)
            .padding(.vertical, Tokens.space3)
            .frame(minHeight: Tokens.targetLarge)
            .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
            .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
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
        .background(Tokens.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
