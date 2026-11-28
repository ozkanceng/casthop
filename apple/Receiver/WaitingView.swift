import CastHopReceiver
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
#if os(macOS)
import AppKit
#endif

/// Builds the QR image. Each module is one pixel, so it must be drawn without smoothing.
enum QrImage {
    static func make(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
}

/// The large QR, 6-box (3+3) connection code, and board identity/IP card. Readable from the back of a room.
struct WaitingView: View {
    @EnvironmentObject private var model: ReceiverModel
    @Environment(\.theme) private var theme

    private var snapshot: ReceiverSnapshot { model.snapshot }
    private var connecting: Bool { snapshot.state == .connecting }
    private var hasQr: Bool { !snapshot.qrUri.isEmpty }
    private var pinDigits: [Character] {
        let chars = Array(snapshot.pin)
        return chars.count == 6 ? chars : Array("------")
    }

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= geometry.size.height
            VStack(spacing: theme.space3) {
                header
                Spacer(minLength: 0)
                if let failure = snapshot.failure {
                    FailureCard(failure: failure)
                } else if wide {
                    let detailsMaxWidth = geometry.size.width * 0.44
                    HStack(spacing: theme.space5) {
                        qrCard(side: min(geometry.size.height * 0.60, geometry.size.width * 0.40))
                        details(maxWidth: detailsMaxWidth).frame(maxWidth: detailsMaxWidth)
                    }
                } else {
                    let detailsMaxWidth = max(280, geometry.size.width - theme.space4 * 2)
                    VStack(spacing: theme.space4) {
                        qrCard(side: min(geometry.size.width * 0.7, geometry.size.height * 0.40))
                        details(maxWidth: detailsMaxWidth)
                    }
                }
                Spacer(minLength: 0)
                toolbar
            }
            .padding(theme.space4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(theme.background.ignoresSafeArea())
    }

    private var header: some View {
        HStack(spacing: theme.space3) {
            Text(verbatim: "CastHop")
                .font(.system(size: theme.fontTitle, weight: .bold))
                .foregroundStyle(theme.primary)

            if snapshot.failure == nil {
                HStack(spacing: theme.space1) {
                    Circle()
                        .fill(connecting ? theme.primary : (hasQr ? theme.success : theme.warning))
                        .frame(width: max(8, 12 * theme.scale), height: max(8, 12 * theme.scale))
                    Text(connecting ? "Connecting…" : (hasQr ? "Ready to connect" : "Offline"))
                        .font(.system(size: theme.fontCaption, weight: .semibold))
                        .foregroundStyle(theme.text)
                }
                .padding(.horizontal, theme.space2)
                .padding(.vertical, theme.space1)
                .background(connecting ? theme.primarySubtle : (hasQr ? theme.successSubtle : theme.surfaceRaised),
                            in: Capsule())
                .overlay(Capsule().stroke(theme.border, lineWidth: 1))
            }

            if snapshot.failure == nil, !connecting, let update = model.visibleUpdate {
                UpdatePill(
                    version: update.latestVersion,
                    onTap: { model.showingUpdateSheet = true },
                    onDismiss: { model.dismissUpdateForSession() }
                )
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
    }

    private func qrCard(side: CGFloat) -> some View {
        ZStack {
            if let image = snapshot.qrUri.isEmpty ? nil : QrImage.make(snapshot.qrUri) {
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .padding(side * 0.05) // the quiet zone
                    .accessibilityLabel(Text("QR code for connecting"))
            } else if snapshot.state != .starting {
                Text("No network connection. Connect the board to Wi-Fi, Ethernet or the tablet's hotspot.")
                    .font(.system(size: theme.fontBody))
                    .foregroundStyle(theme.warning)
                    .multilineTextAlignment(.center)
                    .padding(theme.space3)
            }
        }
        .frame(width: side, height: side)
        .background(snapshot.qrUri.isEmpty ? theme.surface : .white, in: RoundedRectangle(cornerRadius: theme.radius + 4))
        .overlay(
            RoundedRectangle(cornerRadius: theme.radius + 4)
                .stroke(snapshot.qrUri.isEmpty ? theme.border : theme.primarySubtle, lineWidth: max(2, 2 * theme.scale))
        )
    }

    private func pinCells(maxWidth: CGFloat) -> some View {
        let gap = max(6, (10 * theme.scale).rounded())
        let sepW = max(10, (16 * theme.scale).rounded())
        let available = max(220, maxWidth - gap * 6 - sepW)
        let cellW = min((92 * theme.scale).rounded(), floor(available / 6))
        let cellH = (cellW * 1.25).rounded()
        let digitFont = min(theme.fontDisplay, (cellW * 0.72).rounded())

        return HStack(spacing: gap) {
            ForEach(0..<3, id: \.self) { index in
                pinDigitBox(pinDigits[index], width: cellW, height: cellH, fontSize: digitFont)
            }
            Capsule()
                .fill(theme.border)
                .frame(width: sepW, height: max(3, 4 * theme.scale))
            ForEach(3..<6, id: \.self) { index in
                pinDigitBox(pinDigits[index], width: cellW, height: cellH, fontSize: digitFont)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Connection code \(snapshot.pin)"))
    }

    private func pinDigitBox(_ digit: Character, width: CGFloat, height: CGFloat, fontSize: CGFloat) -> some View {
        Text(String(digit))
            .font(.system(size: fontSize, weight: .bold, design: .monospaced))
            .foregroundStyle(theme.text)
            .lineLimit(1)
            .frame(width: width, height: height)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
            .overlay(
                RoundedRectangle(cornerRadius: theme.radius)
                    .stroke(theme.primary, lineWidth: max(2, 2 * theme.scale))
            )
    }

    private func details(maxWidth: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: theme.space3) {
            Text(connecting ? "Connecting…" : "Scan the code with the CastHop app on your tablet")
                .font(.system(size: theme.fontTitle, weight: .semibold))
                .foregroundStyle(theme.text)
                .fixedSize(horizontal: false, vertical: true)
            if connecting {
                ProgressView().controlSize(.large)
            } else {
                Text("or enter this code:")
                    .font(.system(size: theme.fontBody))
                    .foregroundStyle(theme.textMuted)
                pinCells(maxWidth: maxWidth)
            }
            VStack(alignment: .leading, spacing: theme.space1) {
                HStack(spacing: theme.space2) {
                    Text("Board: \(snapshot.name)")
                        .font(.system(size: theme.fontLabel, weight: .semibold))
                        .foregroundStyle(theme.text)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    if !snapshot.shortId.isEmpty {
                        Text(verbatim: "#\(snapshot.shortId)")
                            .font(.system(size: theme.fontCaption, weight: .bold, design: .monospaced))
                            .foregroundStyle(theme.primary)
                            .padding(.horizontal, theme.space2)
                            .padding(.vertical, max(4, theme.space1 / 2))
                            .background(theme.primarySubtle, in: RoundedRectangle(cornerRadius: theme.radius / 2))
                            .overlay(
                                RoundedRectangle(cornerRadius: theme.radius / 2)
                                    .stroke(theme.primary, lineWidth: 1)
                            )
                    }
                }
                if !snapshot.addresses.isEmpty {
                    let portSuffix = snapshot.port > 0 ? " :\(snapshot.port)" : ""
                    Text("Address: \(snapshot.addresses.joined(separator: ", "))\(portSuffix)")
                        .font(.system(size: theme.fontLabel, design: .monospaced))
                        .foregroundStyle(theme.textMuted)
                }
            }
            .padding(theme.space3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
            .overlay(RoundedRectangle(cornerRadius: theme.radius).stroke(theme.border, lineWidth: 1))
        }
    }

    private var toolbar: some View {
        HStack(spacing: theme.space3) {
            Button("Settings") { model.showingSettings = true }
                .buttonStyle(BoardButtonStyle())
#if os(macOS)
            Button("Full screen") { NSApp.keyWindow?.toggleFullScreen(nil) }
                .buttonStyle(BoardButtonStyle())
            Button("Close") {
                model.shutdown()
                NSApp.terminate(nil)
            }
            .buttonStyle(BoardButtonStyle())
#endif
            Text("Version \(model.version)")
                .font(.system(size: theme.fontCaption))
                .foregroundStyle(theme.textMuted)
        }
    }
}

/// A start-up failure in words a teacher can act on.
private struct FailureCard: View {
    let failure: ReceiverFailure
    @Environment(\.theme) private var theme

    private var message: Text {
        switch failure {
        case .identity: return Text("The security certificate could not be created. Restart CastHop.")
        case .noDecoder: return Text("No H.264 video decoder found.")
        case .portInUse(let port): return Text("Port \(String(port)) is in use. Is another copy of CastHop already running?")
        case .listener: return Text("CastHop could not listen on the network. Check the network and restart it.")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space2) {
            Text("CastHop could not start")
                .font(.system(size: theme.fontTitle, weight: .bold))
                .foregroundStyle(theme.danger)
            message
                .font(.system(size: theme.fontBody))
                .foregroundStyle(theme.text)
        }
        .padding(theme.space4)
        .frame(maxWidth: 1100 * theme.scale, alignment: .leading)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
        .overlay(RoundedRectangle(cornerRadius: theme.radius).stroke(theme.danger, lineWidth: 1))
    }
}
