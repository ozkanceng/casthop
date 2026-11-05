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

/// The large QR, the 6-digit code and where this receiver is. Readable from the back of a room.
struct WaitingView: View {
    @EnvironmentObject private var model: ReceiverModel
    @Environment(\.theme) private var theme

    private var snapshot: ReceiverSnapshot { model.snapshot }
    private var connecting: Bool { snapshot.state == .connecting }
    private var pinText: String {
        snapshot.pin.count == 6 ? "\(snapshot.pin.prefix(3)) \(snapshot.pin.suffix(3))" : snapshot.pin
    }

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= geometry.size.height
            VStack(spacing: theme.space3) {
                Text(verbatim: "CastHop")
                    .font(.system(size: theme.fontTitle, weight: .bold))
                    .foregroundStyle(theme.primary)
                Spacer(minLength: 0)
                if let failure = snapshot.failure {
                    FailureCard(failure: failure)
                } else if wide {
                    HStack(spacing: theme.space5) {
                        qrCard(side: min(geometry.size.height * 0.62, geometry.size.width * 0.42))
                        details.frame(maxWidth: geometry.size.width * 0.42)
                    }
                } else {
                    VStack(spacing: theme.space4) {
                        qrCard(side: min(geometry.size.width * 0.7, geometry.size.height * 0.42))
                        details
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
        .background(snapshot.qrUri.isEmpty ? theme.surface : .white, in: RoundedRectangle(cornerRadius: theme.radius))
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: theme.space2) {
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
                Text(verbatim: pinText)
                    .font(.system(size: theme.fontDisplay, weight: .bold, design: .monospaced))
                    .foregroundStyle(theme.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .accessibilityLabel(Text("Connection code \(snapshot.pin)"))
            }
            VStack(alignment: .leading, spacing: theme.space1) {
                Text("Board: \(snapshot.name)")
                    .font(.system(size: theme.fontLabel, weight: .semibold))
                    .foregroundStyle(theme.text)
                    .lineLimit(1)
                if !snapshot.addresses.isEmpty {
                    Text("Address: \(snapshot.addresses.joined(separator: ", "))")
                        .font(.system(size: theme.fontLabel))
                        .foregroundStyle(theme.textMuted)
                }
            }
            .padding(theme.space3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
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
    }
}
