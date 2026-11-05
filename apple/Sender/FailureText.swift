import CastHopSender
import Foundation

/// A failure in the teacher's terms: what happened and what to do. Used by the app and by the extension, whose text iOS
/// shows when a broadcast ends. Each body says what to do next.
extension MirrorFailure {
    var title: String {
        switch self {
        case .unreachable: return String(localized: "Cannot reach the board")
        case .refused: return String(localized: "CastHop is not running on the board")
        case .wrongReceiver: return String(localized: "Security check failed")
        case .authFailed: return String(localized: "The QR code is no longer valid")
        case .wrongPin: return String(localized: "Wrong code")
        case .receiverForgot: return String(localized: "The board no longer knows this tablet")
        case .busyDenied: return String(localized: "The board declined")
        case .busyTimeout: return String(localized: "No answer from the board")
        case .rateLimited: return String(localized: "Too many attempts")
        case .lockedOut: return String(localized: "Temporarily locked")
        case .incompatible: return String(localized: "Incompatible versions")
        case .noEncoder: return String(localized: "This tablet is not supported")
        case .connectionLost: return String(localized: "Connection lost")
        case .encoderFailed: return String(localized: "Video encoding failed")
        case .stoppedByReceiver: return String(localized: "Mirroring was stopped on the board.")
        case .receiverClosed: return String(localized: "CastHop was closed on the board.")
        case .takenOver: return String(localized: "Another tablet took over")
        case .broadcastEnded: return String(localized: "Mirroring ended")
        case .noTarget: return String(localized: "Choose a board first")
        case .internalError: return String(localized: "Something went wrong. Please try again.")
        }
    }

    var body: String? {
        switch self {
        case .unreachable:
            return String(localized: "The tablet and the board must be on the same Wi-Fi network. The school network may block devices from seeing each other: try turning on the tablet's hotspot and connecting the board to it.")
        case .refused: return String(localized: "Open the CastHop app on the board and scan the new QR code.")
        case .wrongReceiver:
            return String(localized: "This QR code belongs to another board or a device on the network is intercepting the connection. Scan the current QR code on the board.")
        case .authFailed: return String(localized: "Each QR code works only once. Scan the new QR code on the board.")
        case .wrongPin:
            return String(localized: "Enter the current 6-digit code shown on the board. After 5 wrong attempts the code changes and you must wait 30 seconds.")
        case .receiverForgot:
            return String(localized: "The pairing may have been removed on the board. Scan the QR code on the board again.")
        case .busyDenied: return String(localized: "Another tablet is mirroring and the switch was not allowed.")
        case .busyTimeout: return String(localized: "The approval prompt on the board was not answered in time. Try again.")
        case .rateLimited: return String(localized: "Wait a few seconds and try again.")
        case .lockedOut: return String(localized: "Too many wrong codes. Wait 30 seconds and use the new code on the board.")
        case .incompatible: return String(localized: "Update the tablet app and CastHop on the board.")
        case .noEncoder: return String(localized: "No suitable video encoder was found on this tablet.")
        case .connectionLost: return String(localized: "Check the Wi-Fi connection and connect again.")
        case .encoderFailed: return String(localized: "Choose \"Balanced\" or \"Weak network\" in Settings and try again.")
        case .takenOver: return String(localized: "The board allowed another tablet to mirror.")
        case .broadcastEnded:
            return String(localized: "Mirroring ends when the screen locks or it is stopped by the system. You can connect again.")
        case .noTarget: return String(localized: "Open CastHop and pick a board before starting the broadcast.")
        case .stoppedByReceiver, .receiverClosed, .internalError: return nil
        }
    }
}
