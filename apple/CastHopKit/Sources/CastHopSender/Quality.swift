import Foundation

/// Stops keyframes from piling up on a slow link. While one is being written, the frames behind it overflow the send
/// queue and each overflow asks for another keyframe. Those requests wait here and leave as one when the write ends.
/// `timeoutUs` bounds the wait, so a stalled write cannot freeze the picture for longer than that.
public final class KeyframeGate: @unchecked Sendable {
    private let timeoutUs: UInt64
    private let lock = NSLock()
    private var writingSinceUs: UInt64?
    private var pending = false

    public init(timeoutUs: UInt64 = 1_000_000) { self.timeoutUs = timeoutUs }

    /// True if the keyframe may be requested now.
    public func onRequest(nowUs: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let since = writingSinceUs, nowUs < since + timeoutUs else { return true }
        pending = true
        return false
    }

    public func onWriteStarted(nowUs: UInt64) {
        lock.lock()
        defer { lock.unlock() }
        writingSinceUs = nowUs
    }

    /// True if a request waited during the write and must be sent now.
    public func onWriteEnded() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        writingSinceUs = nil
        let wanted = pending
        pending = false
        return wanted
    }
}

/// Direction of an automatic quality change.
public enum QualityStep: Sendable {
    case down
    case up
}

/// Decides when a session should move to another preset (ARCHITECTURE.md §7). Time comes from the caller.
///
/// Bitrate adaptation alone cannot save a link that stays below the preset's floor. The bitrate controller rises again
/// after two stable seconds, so a rate that stays at the floor means congestion has not ended, and a rate that stays
/// near the ceiling means the link has room for more.
public final class QualityGovernor: @unchecked Sendable {
    public struct Config: Sendable {
        public var downAfterMs: Int64 = 3_000
        public var upAfterMs: Int64 = 60_000
        public var maxUpAfterMs: Int64 = 480_000
        /// A fall this soon after a rise means the rise failed, so the next one waits twice as long.
        public var relapseWindowMs: Int64 = 30_000
        /// Least time after any switch before a rise.
        public var minGapMs: Int64 = 15_000
        /// Least time after any switch before a fall: a slow link must reach a working preset within seconds.
        public var minGapDownMs: Int64 = 5_000
        public var floorMargin = 1.05
        public var ceilingRatio = 0.9

        public init() {}
    }

    private let config: Config
    private let lock = NSLock()
    private var atFloorSinceMs: Int64?
    private var nearCeilingSinceMs: Int64?
    private var lastSwitchMs: Int64?
    private var lastStep: QualityStep?
    private var upAfterMs: Int64
    private var busy = false

    public init(config: Config = Config()) {
        self.config = config
        upAfterMs = config.upAfterMs
    }

    /// A step to try. The caller must answer with `onSwitched` or `onRejected`.
    public func tick(nowMs: Int64, bitrateBps: Int64, minBps: Int64, maxBps: Int64) -> QualityStep? {
        lock.lock()
        defer { lock.unlock() }
        if busy { return nil }
        atFloorSinceMs = Double(bitrateBps) <= Double(minBps) * config.floorMargin ? atFloorSinceMs ?? nowMs : nil
        nearCeilingSinceMs = Double(bitrateBps) >= Double(maxBps) * config.ceilingRatio ? nearCeilingSinceMs ?? nowMs : nil
        let sinceSwitchMs = lastSwitchMs.map { nowMs - $0 } ?? .max
        let step: QualityStep
        if sinceSwitchMs >= config.minGapDownMs, let since = atFloorSinceMs, nowMs - since >= config.downAfterMs {
            step = .down
        } else if sinceSwitchMs >= config.minGapMs, let since = nearCeilingSinceMs, nowMs - since >= upAfterMs {
            step = .up
        } else {
            return nil
        }
        busy = true
        return step
    }

    public func onSwitched(nowMs: Int64, step: QualityStep) {
        lock.lock()
        defer { lock.unlock() }
        if step == .down, lastStep == .up, let previous = lastSwitchMs, nowMs - previous <= config.relapseWindowMs {
            upAfterMs = min(upAfterMs * 2, config.maxUpAfterMs)
        }
        lastSwitchMs = nowMs
        lastStep = step
        clear()
        busy = false
    }

    /// No lower or higher preset was available: waits a full period before trying again.
    public func onRejected() {
        lock.lock()
        defer { lock.unlock() }
        clear()
        busy = false
    }

    /// Paused or reconnecting: time spent like this says nothing about the link.
    public func clearTimers() {
        lock.lock()
        defer { lock.unlock() }
        clear()
    }

    private func clear() {
        atFloorSinceMs = nil
        nearCeilingSinceMs = nil
    }
}
