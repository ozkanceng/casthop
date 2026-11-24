import CastHopSender
import SwiftUI
import UIKit

/// Visual state for the 6-cell OTP input.
enum OtpVisualState {
    case idle
    case submitting
    case success
    case error
}

/// 6-cell, 3+3 grouped modern OTP code input matching CastHop's Android & board waiting screen layout.
/// Supports paste sanitization ("482 917", "482-917"), iOS `.oneTimeCode` autofill, pulse cursor,
/// spring digit scale, haptic feedback, horizontal shake on error, and automatic submission on the 6th digit.
struct OtpCodeInput: View {
    @Binding var code: String
    var visualState: OtpVisualState = .idle
    var enabled: Bool = true
    var errorSignal: Int = 0
    var autoFocus: Bool = true
    var onComplete: (String) -> Void

    @FocusState private var isFocused: Bool
    @State private var shakeOffset: CGFloat = 0
    @State private var pulse = false
    @State private var lastAutoSubmitted: String?

    var body: some View {
        ZStack {
            // Hidden functional TextField handling keyboard, paste, backspace, and iOS autofill.
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($isFocused)
                .disabled(!enabled)
                .frame(width: 1, height: 1)
                .opacity(0.01)
                .accessibilityLabel(Text("6-digit connection code, \(code.count) of 6 digits entered"))
                .onChange(of: code) { oldValue, newValue in
                    let clean = BoardIdentity.sanitizeOtp(newValue)
                    if clean != newValue {
                        code = clean
                        return
                    }
                    if clean.count > oldValue.count {
                        UISelectionFeedbackGenerator().selectionChanged()
                    }
                    if clean.count < 6 {
                        lastAutoSubmitted = nil
                    } else if enabled && visualState == .idle && clean != lastAutoSubmitted {
                        lastAutoSubmitted = clean
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        onComplete(clean)
                    }
                }

            GeometryReader { geo in
                let gap: CGFloat = geo.size.width < 340 ? 6 : 9
                let groupGap: CGFloat = geo.size.width < 340 ? 12 : 18
                let available = max(geo.size.width - gap * 4 - groupGap, 180)
                let cellWidth = min(available / 6, 58)
                let cellHeight = min(max(cellWidth * 1.28, 52), 74)
                let digitFont: CGFloat = cellWidth < 40 ? 22 : (cellWidth < 48 ? 26 : 32)
                let digits = Array(code)

                HStack(spacing: 0) {
                    ForEach(0..<6, id: \.self) { index in
                        if index == 3 {
                            Capsule()
                                .fill(Tokens.outlineVariant)
                                .frame(width: 8, height: 3)
                                .frame(width: groupGap, height: cellHeight)
                        } else if index > 0 {
                            Spacer().frame(width: gap)
                        }

                        let digit = index < digits.count ? digits[index] : nil
                        let active = enabled && isFocused && (index == digits.count || (digits.count == 6 && index == 5))

                        OtpDigitCell(
                            digit: digit,
                            active: active,
                            visualState: visualState,
                            pulse: pulse,
                            width: cellWidth,
                            height: cellHeight,
                            fontSize: digitFont
                        )
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(height: 76)
            .contentShape(Rectangle())
            .onTapGesture {
                if enabled { isFocused = true }
            }
        }
        .offset(x: shakeOffset)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.75).repeatForever(autoreverses: true)) {
                pulse = true
            }
            if autoFocus && enabled {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { isFocused = true }
            }
        }
        .onChange(of: errorSignal) { _, signal in
            guard signal > 0 else { return }
            lastAutoSubmitted = nil
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            Task { @MainActor in
                for dx in [-14.0, 14.0, -10.0, 10.0, -5.0, 5.0, 0.0] {
                    withAnimation(.easeInOut(duration: 0.042)) { shakeOffset = dx }
                    try? await Task.sleep(for: .milliseconds(42))
                }
            }
        }
    }
}

private struct OtpDigitCell: View {
    let digit: Character?
    let active: Bool
    let visualState: OtpVisualState
    let pulse: Bool
    let width: CGFloat
    let height: CGFloat
    let fontSize: CGFloat

    private var filled: Bool { digit != nil }

    private var borderColor: Color {
        switch visualState {
        case .error: return Tokens.error
        case .success: return Tokens.tertiary
        default:
            if active { return Tokens.primary.opacity(pulse ? 1.0 : 0.45) }
            if filled { return Tokens.primary.opacity(0.65) }
            return Tokens.outlineVariant
        }
    }

    private var borderWidth: CGFloat {
        if visualState == .error || visualState == .success || active { return 2.5 }
        return filled ? 1.5 : 1
    }

    private var backgroundColor: Color {
        switch visualState {
        case .error: return Tokens.errorContainer.opacity(0.5)
        case .success: return Tokens.tertiaryContainer.opacity(0.5)
        default:
            if active { return Tokens.primaryContainer.opacity(0.35) }
            return filled ? Tokens.surface : Tokens.surfaceLow
        }
    }

    private var textColor: Color {
        switch visualState {
        case .error: return Tokens.error
        case .success: return Tokens.tertiary
        default: return Tokens.onSurface
        }
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .fill(backgroundColor)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(borderColor, lineWidth: borderWidth))
                .shadow(color: active ? Tokens.primary.opacity(0.16) : .clear, radius: 6, y: 2)

            if let digit {
                Text(String(digit))
                    .font(.system(size: fontSize, weight: .bold, design: .monospaced))
                    .foregroundStyle(textColor)
                    .lineLimit(1)
                    .transition(.opacity)
            } else if active {
                Capsule()
                    .fill(Tokens.primary)
                    .frame(width: 2.5, height: 26)
                    .opacity(pulse ? 1 : 0.15)
            }
        }
        .frame(width: width, height: height)
        .animation(.easeInOut(duration: 0.16), value: active)
    }
}

/// Miniature illustration showing where to find the 6-digit code on the board's waiting screen.
struct BoardCodeIllustrationCard: View {
    var body: some View {
        HStack(spacing: Tokens.space4) {
            Canvas { ctx, size in
                let r = size.height * 0.12
                let rect = CGRect(origin: .zero, size: size)
                ctx.fill(Path(roundedRect: rect, cornerRadius: r), with: .color(Tokens.surface))
                ctx.stroke(Path(roundedRect: rect.insetBy(dx: 1, dy: 1), cornerRadius: r),
                           with: .color(Tokens.outline), lineWidth: 1.8)
                let qrSide = size.height * 0.56
                let qrRect = CGRect(x: size.width * 0.1, y: (size.height - qrSide) / 2, width: qrSide, height: qrSide)
                ctx.fill(Path(roundedRect: qrRect, cornerRadius: 3), with: .color(Tokens.onSurfaceVariant.opacity(0.42)))

                let codeRect = CGRect(x: size.width * 0.48, y: size.height * 0.32,
                                      width: size.width * 0.44, height: size.height * 0.36)
                ctx.fill(Path(roundedRect: codeRect, cornerRadius: 4), with: .color(Tokens.primaryContainer))
                ctx.stroke(Path(roundedRect: codeRect, cornerRadius: 4), with: .color(Tokens.primary), lineWidth: 1.5)

                let dotY = codeRect.midY
                for i in 0..<6 {
                    let extra: CGFloat = i >= 3 ? 3 : 0
                    let x = min(codeRect.maxX - 4, codeRect.minX + 5 + CGFloat(i) * ((codeRect.width - 13) / 5) + extra * 0.5)
                    let dot = CGRect(x: x - 2, y: dotY - 2, width: 4, height: 4)
                    ctx.fill(Path(ellipseIn: dot), with: .color(Tokens.primary))
                }
            }
            .frame(width: 96, height: 62)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: Tokens.space1) {
                HStack(spacing: 6) {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(Tokens.primary)
                        .font(.subheadline)
                    Text("Where is the 6-digit code?")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Tokens.onSurface)
                }
                Text("Open CastHop on the board — the code appears in large digits (e.g. 482 917) to the right of the QR code.")
                    .font(.subheadline)
                    .foregroundStyle(Tokens.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(Tokens.space4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surfaceLow, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
    }
}

/// Unified 3-step animated connection progress card used across Home, PIN, and Manual IP flows.
struct ConnectionStepperCard: View {
    let boardName: String
    let activeStep: Int
    var waitingApproval: Bool = false
    var hint: LocalizedStringKey?
    let onCancel: () -> Void

    private var stepLabels: [LocalizedStringKey] {
        [
            "Searching for board…",
            waitingApproval ? "Waiting for approval on board…" : "Securing connection…",
            "Starting stream…",
        ]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space4) {
            if !boardName.isEmpty {
                Text(verbatim: boardName)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Tokens.primary)
            }
            VStack(alignment: .leading, spacing: 14) {
                ForEach(0..<stepLabels.count, id: \.self) { index in
                    let done = index < activeStep
                    let current = index == activeStep
                    HStack(spacing: Tokens.space3) {
                        ZStack {
                            Circle()
                                .fill(done ? Tokens.tertiary : current ? Tokens.primaryContainer : Tokens.surfaceContainer)
                                .frame(width: 28, height: 28)
                            if done {
                                Image(systemName: "checkmark")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.white)
                            } else if current {
                                ProgressView()
                                    .scaleEffect(0.75)
                                    .tint(Tokens.primary)
                            } else {
                                Text(verbatim: "\(index + 1)")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(Tokens.onSurfaceVariant)
                            }
                        }
                        Text(stepLabels[index])
                            .font(current ? .headline : .body)
                            .foregroundStyle(done ? Tokens.tertiary : current ? Tokens.onSurface : Tokens.onSurfaceVariant)
                    }
                }
            }
            if let hint {
                Text(hint)
                    .font(.subheadline)
                    .foregroundStyle(Tokens.onSurfaceVariant)
            }
            Button("Cancel", action: onCancel)
                .buttonStyle(OutlineButtonStyle())
        }
        .padding(Tokens.space5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant, lineWidth: 1))
    }
}

/// Actionable error card with hotspot guidance, 1-tap Retry, and Manual IP shortcut.
struct ConnectionErrorCard: View {
    let failure: MirrorFailure?
    var onRetry: (() -> Void)?
    var onManualIp: (() -> Void)?
    var onGuide: (() -> Void)?
    var onDismiss: (() -> Void)?

    private var isError: Bool { failure != nil }
    private var showHotspotHelp: Bool {
        failure == .unreachable || failure == .clientIsolation
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.space3) {
            HStack(spacing: Tokens.space2) {
                if isError {
                    Image(systemName: showHotspotHelp ? "wifi.slash" : "exclamationmark.circle.fill")
                        .font(.title3)
                }
                Text(verbatim: failure?.title ?? String(localized: "Mirroring stopped."))
                    .font(.title3.weight(.semibold))
            }
            if let body = failure?.body {
                Text(verbatim: body)
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if showHotspotHelp {
                Text("If the school network blocks it: use the tablet's hotspot")
                    .font(.headline)
                    .padding(.top, Tokens.space1)
                StepList(steps: GuideSteps.hotspot)
            }
            if isError && (onRetry != nil || (onManualIp != nil && showHotspotHelp)) {
                HStack(spacing: Tokens.space2) {
                    if let onRetry {
                        Button(action: onRetry) {
                            Label("Try again", systemImage: "arrow.clockwise")
                                .font(.headline)
                                .foregroundStyle(Tokens.onPrimary)
                                .frame(maxWidth: .infinity, minHeight: Tokens.targetSmall)
                                .background(Tokens.primary, in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
                        }
                        .buttonStyle(.plain)
                    }
                    if let onManualIp, showHotspotHelp {
                        Button(action: onManualIp) {
                            Text("Connect by IP address")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Tokens.onPrimaryContainer)
                                .frame(maxWidth: .infinity, minHeight: Tokens.targetSmall)
                                .background(Tokens.primaryContainer, in: RoundedRectangle(cornerRadius: Tokens.radiusSmall))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            if let onGuide, showHotspotHelp {
                Button("How do I connect?", action: onGuide)
                    .font(.subheadline.weight(.semibold))
                    .frame(minHeight: Tokens.targetSmall)
            } else if let onDismiss, !isError {
                Button("OK", action: onDismiss)
                    .font(.subheadline.weight(.semibold))
                    .frame(minHeight: Tokens.targetSmall)
            }
        }
        .foregroundStyle(isError ? Tokens.onErrorContainer : Tokens.onSurface)
        .padding(Tokens.space4 + Tokens.space1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isError ? Tokens.errorContainer : Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
        .overlay(
            RoundedRectangle(cornerRadius: Tokens.radius)
                .stroke(isError ? Color.clear : Tokens.outlineVariant, lineWidth: 1)
        )
    }
}

/// Animated shimmer skeleton rows while Bonjour discovery searches for boards.
struct BoardSkeletonList: View {
    var count: Int = 3
    @State private var shimmer = false

    var body: some View {
        VStack(spacing: Tokens.space3) {
            ForEach(0..<count, id: \.self) { _ in
                HStack(spacing: Tokens.space4) {
                    Circle()
                        .fill(Tokens.surfaceContainer)
                        .frame(width: 42, height: 42)
                    VStack(alignment: .leading, spacing: 8) {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(Tokens.surfaceContainer)
                            .frame(width: 150, height: 15)
                        RoundedRectangle(cornerRadius: 6)
                            .fill(Tokens.surfaceLow)
                            .frame(width: 110, height: 12)
                    }
                    Spacer()
                }
                .padding(.horizontal, Tokens.space4)
                .frame(height: 74)
                .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.radius))
                .overlay(RoundedRectangle(cornerRadius: Tokens.radius).stroke(Tokens.outlineVariant.opacity(0.6), lineWidth: 1))
                .opacity(shimmer ? 0.85 : 0.4)
            }
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                shimmer = true
            }
        }
    }
}

/// Sheet to assign or clear a custom nickname for a remembered board.
struct RenameBoardSheet: View {
    let rawName: String
    let currentNickname: String?
    let onSave: (String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(rawName, text: $text)
                        .onChange(of: text) { _, v in
                            if v.count > 48 { text = String(v.prefix(48)) }
                        }
                } header: {
                    Text("Nickname")
                } footer: {
                    Text("Enter a classroom or room name (e.g. Room 9-B). Leave blank to use the board's own name.")
                }
            }
            .navigationTitle("Name this board")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(clean.isEmpty ? nil : clean)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .onAppear { text = currentNickname ?? "" }
        }
        .presentationDetents([.medium])
    }
}

/// Live pulsing indicator pill for active mirroring sessions.
struct LivePillBadge: View {
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(Tokens.tertiary)
                .frame(width: 8, height: 8)
                .scaleEffect(pulse ? 1.25 : 0.85)
                .opacity(pulse ? 1 : 0.45)
            Text("Live")
                .font(.caption.weight(.bold))
                .foregroundStyle(Tokens.onTertiaryContainer)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Tokens.tertiaryContainer, in: Capsule())
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}
