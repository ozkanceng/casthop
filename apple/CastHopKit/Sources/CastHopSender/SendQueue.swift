import CastHopProtocol
import Foundation

/// An encoded video frame. `flags` are protocol frame flags (CONFIG, KEYFRAME).
public struct EncodedFrame: @unchecked Sendable {
    public var data: Data
    public var ptsUs: UInt64
    public var flags: UInt16
    public var enqueuedUs: UInt64

    public init(data: Data, ptsUs: UInt64, flags: UInt16, enqueuedUs: UInt64) {
        self.data = data
        self.ptsUs = ptsUs
        self.flags = flags
        self.enqueuedUs = enqueuedUs
    }

    public var isKeyframe: Bool { flags & FrameFlags.keyframe != 0 }
    public var isConfig: Bool { flags & FrameFlags.config != 0 }
}

/// The bounded queue between encoder and network writer (ARCHITECTURE.md §2.1).
///
/// When the network slows down it drops frames instead of building delay: at `maxFrames`, or when the oldest frame is
/// older than `maxAgeUs`, pending P-frames go and no P-frame is accepted until the next keyframe. P-frames depend on the
/// frames before them, so they cannot be dropped one by one. CONFIG is never dropped, and a new CONFIG clears frames of
/// the old format. `onDrop` lets the caller ask for an IDR and lower the bitrate.
public final class SendQueue: @unchecked Sendable {
    private let maxFrames: Int
    private let maxAgeUs: UInt64
    private let onDrop: (Int) -> Void
    private let condition = NSCondition()
    private var queue: [EncodedFrame] = []
    private var waitingForKeyframe = false
    private var discontinuity = false
    private var closed = false
    private var dropped: Int64 = 0

    public init(maxFrames: Int = 2, maxAgeUs: UInt64 = 50_000, onDrop: @escaping (Int) -> Void = { _ in }) {
        self.maxFrames = maxFrames
        self.maxAgeUs = maxAgeUs
        self.onDrop = onDrop
    }

    public var droppedTotal: Int64 {
        condition.lock()
        defer { condition.unlock() }
        return dropped
    }

    /// Age of the oldest frame in µs, 0 if the queue is empty.
    public func oldestAgeUs(nowUs: UInt64) -> Int64 {
        condition.lock()
        defer { condition.unlock() }
        guard let first = queue.first else { return 0 }
        return Int64(nowUs) - Int64(first.enqueuedUs)
    }

    /// True if the frame was accepted.
    @discardableResult
    public func offer(_ frame: EncodedFrame, nowUs: UInt64) -> Bool {
        var droppedNow = 0
        condition.lock()
        let accepted = accept(frame, nowUs: nowUs, dropped: &droppedNow)
        dropped += Int64(droppedNow)
        condition.unlock()
        if droppedNow > 0 { onDrop(droppedNow) }
        return accepted
    }

    private func accept(_ frame: EncodedFrame, nowUs: UInt64, dropped: inout Int) -> Bool {
        if closed { return false }
        if frame.isConfig {
            dropped = queue.lazy.filter { !$0.isConfig }.count
            queue.removeAll()
            queue.append(frame)
            condition.signal()
            return true
        }
        if waitingForKeyframe && !frame.isKeyframe {
            dropped = 1
            discontinuity = true
            return false
        }
        if frame.isKeyframe { waitingForKeyframe = false }

        let stale = queue.first.map { nowUs > $0.enqueuedUs && nowUs - $0.enqueuedUs > maxAgeUs } ?? false
        if queue.count >= maxFrames || stale {
            let before = queue.count
            queue.removeAll { !$0.isConfig }
            dropped = before - queue.count
            discontinuity = discontinuity || dropped > 0
            if !frame.isKeyframe {
                // This P-frame depends on the frames just dropped.
                dropped += 1
                waitingForKeyframe = true
                return false
            }
        }
        queue.append(frame)
        condition.signal()
        return true
    }

    /// Waits for the next frame. The flag is true if frames were dropped before it (DISCONTINUITY). Nil on timeout or
    /// after close.
    public func take(timeoutMs: Int) -> (EncodedFrame, Bool)? {
        let deadline = Date(timeIntervalSinceNow: Double(timeoutMs) / 1000)
        condition.lock()
        defer { condition.unlock() }
        while queue.isEmpty && !closed {
            if timeoutMs <= 0 || !condition.wait(until: deadline) {
                if queue.isEmpty { return nil }
                break
            }
        }
        guard !queue.isEmpty else { return nil }
        let frame = queue.removeFirst()
        let disc = discontinuity && !frame.isConfig
        if disc { discontinuity = false }
        return (frame, disc)
    }

    /// Drops everything pending and waits for the next keyframe (after a reconnect).
    public func dropUntilKeyframe() {
        condition.lock()
        defer { condition.unlock() }
        let before = queue.count
        queue.removeAll { !$0.isConfig }
        dropped += Int64(before - queue.count)
        waitingForKeyframe = true
        discontinuity = true
    }

    public func close() {
        condition.lock()
        defer { condition.unlock() }
        closed = true
        queue.removeAll()
        condition.broadcast()
    }
}

/// An encoded audio packet: a raw AAC access unit, no ADTS.
public struct AudioPacket: @unchecked Sendable {
    public var data: Data
    public var ptsUs: UInt64

    public init(data: Data, ptsUs: UInt64) {
        self.data = data
        self.ptsUs = ptsUs
    }
}

/// Bounded audio queue. Every packet decodes on its own, so the oldest goes when it is full.
public final class AudioQueue: @unchecked Sendable {
    private let capacity: Int
    private let condition = NSCondition()
    private var queue: [AudioPacket] = []
    private var closed = false
    private var droppedCount: Int64 = 0

    public init(capacity: Int = 10) { self.capacity = capacity }

    public var dropped: Int64 {
        condition.lock()
        defer { condition.unlock() }
        return droppedCount
    }

    public func offer(_ packet: AudioPacket) {
        condition.lock()
        defer { condition.unlock() }
        if closed { return }
        while queue.count >= capacity {
            queue.removeFirst()
            droppedCount += 1
        }
        queue.append(packet)
        condition.signal()
    }

    public func clear() {
        condition.lock()
        defer { condition.unlock() }
        queue.removeAll()
    }

    public func take(timeoutMs: Int) -> AudioPacket? {
        let deadline = Date(timeIntervalSinceNow: Double(timeoutMs) / 1000)
        condition.lock()
        defer { condition.unlock() }
        while queue.isEmpty && !closed {
            if !condition.wait(until: deadline) { break }
        }
        return queue.isEmpty ? nil : queue.removeFirst()
    }

    public func close() {
        condition.lock()
        defer { condition.unlock() }
        closed = true
        queue.removeAll()
        condition.broadcast()
    }
}
