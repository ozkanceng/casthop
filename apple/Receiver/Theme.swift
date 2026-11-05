import SwiftUI

/// Design tokens for the receiver, the same values as `receiver/src/qml/Theme.qml`. Sized for a 1920×1080 canvas read from
/// 2–5 m and scaled to the window. Colours meet WCAG AA (text on background ≥ 4.5:1) in both themes.
struct Theme: Equatable {
    var dark = true
    var scale: CGFloat = 1

    private func pick(_ d: UInt32, _ l: UInt32) -> Color { Color(hex: dark ? d : l) }

    var background: Color { pick(0x0F1720, 0xF4F6F9) }
    var surface: Color { pick(0x18222E, 0xFFFFFF) }
    var surfaceRaised: Color { pick(0x223040, 0xE3E9F0) }
    var primary: Color { pick(0x4DA3FF, 0x0B5CAD) }
    var primaryForeground: Color { pick(0x04172B, 0xFFFFFF) }
    var text: Color { pick(0xF2F5F8, 0x0F1720) }
    var textMuted: Color { pick(0xA9B6C4, 0x4A5868) }
    var warning: Color { pick(0xFFB86E, 0x8A4B00) }
    var danger: Color { pick(0xFF7A7A, 0xB3261E) }
    var scrim: Color { (dark ? Color(hex: 0x0F1720) : Color(hex: 0xF4F6F9)).opacity(0.85) }

    var space1: CGFloat { (8 * scale).rounded() }
    var space2: CGFloat { (16 * scale).rounded() }
    var space3: CGFloat { (24 * scale).rounded() }
    var space4: CGFloat { (40 * scale).rounded() }
    var space5: CGFloat { (64 * scale).rounded() }

    /// Never below 72 px, however small the window.
    var touchTarget: CGFloat { max(72, (88 * scale).rounded()) }
    var radius: CGFloat { (16 * scale).rounded() }

    var fontDisplay: CGFloat { (96 * scale).rounded() }
    var fontTitle: CGFloat { (48 * scale).rounded() }
    var fontBody: CGFloat { (30 * scale).rounded() }
    var fontLabel: CGFloat { (24 * scale).rounded() }
    var fontCaption: CGFloat { (20 * scale).rounded() }

    /// 1 on a 1920×1080 canvas, in either orientation.
    static func scale(for size: CGSize) -> CGFloat {
        let long = max(size.width, size.height)
        let short = min(size.width, size.height)
        return max(0.45, min(long / 1920, short / 1080, 2))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: 1)
    }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue = Theme()
}

extension EnvironmentValues {
    var theme: Theme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

/// A large button that shows focus clearly on Apple TV, where nothing else tells the viewer where they are.
struct BoardButtonStyle: ButtonStyle {
    enum Kind {
        case primary
        case plain
        case danger
    }

    var kind = Kind.plain

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, kind: kind)
    }

    private struct Styled: View {
        let configuration: Configuration
        let kind: Kind
        @Environment(\.theme) private var theme
        @Environment(\.isFocused) private var focused

        private var fill: Color {
            switch kind {
            case .primary: return theme.primary
            case .plain: return theme.surfaceRaised
            case .danger: return theme.danger
            }
        }

        private var ink: Color {
            switch kind {
            case .primary, .danger: return theme.primaryForeground
            case .plain: return theme.text
            }
        }

        var body: some View {
            configuration.label
                .font(.system(size: theme.fontLabel, weight: .semibold))
                .foregroundStyle(ink)
                .padding(.horizontal, theme.space3)
                .frame(minHeight: theme.touchTarget)
                .background(fill, in: RoundedRectangle(cornerRadius: theme.radius))
                .overlay {
                    RoundedRectangle(cornerRadius: theme.radius)
                        .stroke(focusRing, lineWidth: max(4, 6 * theme.scale))
                }
                .scaleEffect(focusScale)
                .opacity(configuration.isPressed ? 0.8 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }

        private var focusRing: Color {
#if os(tvOS)
            focused ? theme.text : .clear
#else
            .clear
#endif
        }

        private var focusScale: CGFloat {
#if os(tvOS)
            focused ? 1.06 : 1
#else
            1
#endif
        }
    }
}
