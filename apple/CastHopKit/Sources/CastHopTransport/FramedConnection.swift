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
    /// Early frames received before a handler is installed are buffered and replayed in order on `queue`.
    public var onFrame: ((Frame, UInt64) -> Void)? {
        get {
            handlerLock.lock()
            defer { handlerLock.unlock() }
            return _onFrame
        }
        set {
            handlerLock.lock()
            _onFrame = newValue
            handlerLock.unlock()
            queue.async { [weak self] in
                guard let self else { return }
                self.frameHandler = newValue
                if let handler = newValue, !self.pendingFrames.isEmpty {
                    let queued = self.pendingFrames
                    self.pendingFrames.removeAll(keepingCapacity: false)
                    for (frame, ts) in queued {
                        handler(frame, ts)
                    }
                }
            }
        }
    }

    /// Runs once. The error is nil for a clean close.
    /// If the connection already closed before this handler is installed, it fires immediately on `queue`.
    public var onClosed: ((Error?) -> Void)? {
        get {
            handlerLock.lock()
            defer { handlerLock.unlock() }
            return _onClosed
        }
        set {
            handlerLock.lock()
            _onClosed = newValue
            handlerLock.unlock()
            queue.async { [weak self] in
                guard let self else { return }
                self.closeHandler = newValue
                if self.finished, !self.closeNotified, let handler = newValue {
                    self.closeNotified = true
                    self.closeHandler = nil
                    handler(self.finishedError)
                }
            }
        }
    }

    private let handlerLock = NSLock()
    private var _onFrame: ((Frame, UInt64) -> Void)?
    private var _onClosed: ((Error?) -> Void)?

    // Queue-confined state (mutated only on `queue`).
    private var frameHandler: ((Frame, UInt64) -> Void)?
    private var closeHandler: ((Error?) -> Void)?
    private var pendingFrames: [(Frame, UInt64)] = []
    private var parser = FrameParser()
    private var sendSeq: UInt32 = 0
    private let sendLock = NSLock()
    private var closing = false
    private var finished = false
    private var closeNotified = false
    private var finishedError: Error?
    private static let readChunk = 256 * 1024
    private static let maxPendingFrames = 64

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
                    if let handler = self.frameHandler {
                        handler(frame, now)
                    } else if self.pendingFrames.count < Self.maxPendingFrames {
                        self.pendingFrames.append((frame, now))
                    }
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
        queue.async { [weak self] in
            guard let self, !self.closing, !self.finished else { return }
            self.closing = true
            self.connection.send(content: nil, contentContext: .finalMessage, isComplete: true,
                                 completion: .contentProcessed { [weak self] _ in self?.connection.cancel() })
            self.queue.asyncAfter(deadline: .now() + 2) { [weak self] in
                guard let self, !self.finished else { return }
                self.connection.forceCancel()
            }
        }
    }

    /// Closes at once.
    public func abort(_ error: Error? = nil) {
        connection.forceCancel()
        queue.async { [weak self] in
            guard let self else { return }
            self.closing = true
            self.finish(error)
        }
    }

    private func finish(_ error: Error?) {
        guard !finished else { return }
        finished = true
        closing = true
        finishedError = error
        connection.stateUpdateHandler = nil
        let handler = closeHandler
        closeHandler = nil
        frameHandler = nil
        if let handler {
            closeNotified = true
            handler(error)
        }
    }
}
