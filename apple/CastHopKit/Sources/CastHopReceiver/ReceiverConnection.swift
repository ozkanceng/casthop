import CastHopProtocol
import CastHopTransport
import Foundation
import Network

/// One accepted connection (PROTOCOL.md §4, §11). It sends CHALLENGE, watches the deadlines and tells control and media
/// frames apart. Runs on the receiver queue.
final class ReceiverConnection {
    enum Role {
        case unknown
        case pending
        case control
        case media
    }

    static let firstMessageTimeoutUs: UInt64 = 10_000_000
    static let controlSilenceTimeoutUs: UInt64 = 5_000_000

    let id: ConnId
    let serverNonce = Auth.randomBytes(ControlCodec.idSize)
    private let framed: FramedConnection
    private let receiverName: String
    private(set) var role = Role.unknown
    private var deadlineUs: UInt64?
    private var closed = false
    private var established = false

    var onControl: ((ReceiverConnection, ControlMessage, UInt64) -> Void)?
    var onMedia: ((ReceiverConnection, FrameHeader, Data, UInt64) -> Void)?
    var onClosed: ((ReceiverConnection) -> Void)?

    init(id: ConnId, connection: NWConnection, queue: DispatchQueue, receiverName: String) {
        self.id = id
        framed = FramedConnection(connection: connection, queue: queue)
        self.receiverName = receiverName
    }

    func start() {
        deadlineUs = MonotonicClock.nowUs() + Self.firstMessageTimeoutUs
        framed.connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.ready()
            case .failed, .cancelled: self.finish()
            default: break
            }
        }
        framed.connection.start(queue: framed.queue)
    }

    private func ready() {
        guard TLSSettings.negotiatedExpected(framed.connection) else {
            abort()
            return
        }
        established = true
        framed.onFrame = { [weak self] frame, recvUs in self?.handle(frame, recvUs: recvUs) }
        framed.onClosed = { [weak self] _ in self?.finish() }
        framed.startReading()
        send(.challenge(Challenge(minVersion: 1, maxVersion: 1, serverNonce: serverNonce,
                                  receiverName: receiverName.isEmpty ? nil : receiverName)))
        deadlineUs = MonotonicClock.nowUs() + Self.firstMessageTimeoutUs
    }

    func setRole(_ newRole: Role) {
        role = newRole
        // Media may stay silent during PAUSE, and a connection waiting for approval has nothing to say.
        deadlineUs = newRole == .control ? MonotonicClock.nowUs() + Self.controlSilenceTimeoutUs : nil
    }

    func checkDeadline(nowUs: UInt64) {
        if let d = deadlineUs, nowUs >= d { abort() }
    }

    func send(_ msg: ControlMessage) {
        guard established, !closed else { return }
        framed.send(control: msg)
    }

    func closeGracefully() {
        deadlineUs = nil
        if established {
            framed.closeGracefully()
        } else {
            abort()
        }
    }

    func abort() {
        deadlineUs = nil
        if established {
            framed.abort()
        } else {
            framed.connection.forceCancel()
            finish()
        }
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        deadlineUs = nil
        framed.connection.stateUpdateHandler = nil
        let handler = onClosed
        onClosed = nil
        onControl = nil
        onMedia = nil
        handler?(self)
    }

    private func violation() {
        switch role {
        case .control, .pending: send(.bye(reason: ByeReason.malformed))
        case .unknown: send(.reject(reason: RejectReason.malformed, retryAfterMs: nil))
        case .media: break
        }
        closeGracefully()
    }

    private func handle(_ frame: Frame, recvUs: UInt64) {
        if role == .control { deadlineUs = MonotonicClock.nowUs() + Self.controlSilenceTimeoutUs }
        guard frame.header.type == .control else {
            if role == .media {
                onMedia?(self, frame.header, frame.payload, recvUs)
            } else {
                violation()
            }
            return
        }
        if role == .media {
            violation()
            return
        }
        do {
            onControl?(self, try ControlCodec.decode(frame.payload), recvUs)
        } catch {
            violation()
        }
    }
}
