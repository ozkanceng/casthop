import CastHopProtocol
import Foundation
import Network

public enum TransportError: Error, Equatable, Sendable {
    /// The certificate is not the expected one: a possible interception.
    case fingerprintMismatch(expected: Data, actual: Data)
    /// TLS 1.3 or ALPN `yansit/1` was not negotiated.
    case incompatibleReceiver
    case connectFailed(refused: Bool)
    case timeout
    case closed
    case malformed(ProtocolError)
}

/// One TLS connection carrying protocol frames. Callbacks run on `queue`. Sending is safe from any thread.
public final class FramedConnection: @unchecked Sendable {
    public let connection: NWConnection
    public let queue: DispatchQueue

    /// A complete frame and the receive time in µs.
    public var onFrame: ((Frame, UInt64) -> Void)?
    /// Runs once. The error is nil for a clean close.
    public var onClosed: ((Error?) -> Void)?

    private var parser = FrameParser()
    private var sendSeq: UInt32 = 0
    private let sendLock = NSLock()
    private var closing = false
    private var finished = false
    private static let readChunk = 256 * 1024

    public init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    public var isOpen: Bool { !closing && !finished }

    public func startReading() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let error): self?.finish(error)
            case .cancelled: self?.finish(nil)
            default: break
            }
        }
        receiveNext()
    }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: Self.readChunk) { [weak self] data, _, complete, error in
            guard let self, !self.finished else { return }
            if let data, !data.isEmpty {
                let now = MonotonicClock.nowUs()
                do {
                    try self.parser.feed(data)
                } catch let e as ProtocolError {
                    self.abort(TransportError.malformed(e))
                    return
                } catch {
                    self.abort(error)
                    return
                }
                while self.isOpen, let frame = self.parser.next() {
                    self.onFrame?(frame, now)
                }
            }
            if self.finished { return }
            if let error {
                self.abort(error)
            } else if complete {
                self.abort(self.parser.atBoundary ? nil : TransportError.malformed(.truncated))
            } else {
                self.receiveNext()
            }
        }
    }

    /// Sends a frame. `completion` runs when the stack has taken the data, which gives the caller back-pressure.
    public func send(type: FrameType, flags: UInt16 = 0, ptsUs: UInt64, payload: Data,
                     completion: ((Error?) -> Void)? = nil) {
        sendLock.lock()
        defer { sendLock.unlock() }
        guard !finished else {
            completion?(TransportError.closed)
            return
        }
        let header = Frames.encodeHeader(FrameHeader(type: type, flags: flags, ptsUs: ptsUs, seq: sendSeq,
                                                     length: payload.count))
        sendSeq &+= 1
        connection.batch {
            connection.send(content: header, completion: .idempotent)
            connection.send(content: payload, completion: .contentProcessed { error in completion?(error) })
        }
    }

    public func send(control msg: ControlMessage, completion: ((Error?) -> Void)? = nil) {
        send(type: .control, ptsUs: MonotonicClock.nowUs(), payload: ControlCodec.encode(msg), completion: completion)
    }

    /// Flushes pending writes, then closes (at most 2 s later).
    public func closeGracefully() {
        guard !closing, !finished else { return }
        closing = true
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true,
                        completion: .contentProcessed { [weak self] _ in self?.connection.cancel() })
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, !self.finished else { return }
            self.connection.forceCancel()
        }
    }

    /// Closes at once.
    public func abort(_ error: Error? = nil) {
        closing = true
        connection.forceCancel()
        finish(error)
    }

    private func finish(_ error: Error?) {
        guard !finished else { return }
        finished = true
        closing = true
        connection.stateUpdateHandler = nil
        let handler = onClosed
        onClosed = nil
        onFrame = nil
        handler?(error)
    }
}
