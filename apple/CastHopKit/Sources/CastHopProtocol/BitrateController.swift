import Foundation

/// AIMD bitrate controller (PROTOCOL.md §7.6). Pure logic: time comes from the caller.
public final class BitrateController {
    public struct Config {
        public var initialBps: Int64
        public var minBps: Int64
        public var maxBps: Int64
        public var decreaseFactor = 0.7
        /// Per tick; 1.02 every 250 ms is about 8% per second.
        public var increaseFactor = 1.02
        /// Growth per tick back towards the rate that worked before congestion (about 31% per second).
        public var recoveryIncreaseFactor = 1.07
        /// Share of the earlier rate that is regained quickly.
        public var recoveryTargetRatio = 0.85
        public var tickMs: Int64 = 250
        public var decreaseHoldMs: Int64 = 500
        public var stableMsBeforeIncrease: Int64 = 2000
        public var queueDelayThresholdUs: Int64 = 50_000
        public var delaySlopeThresholdUsPerSec: Int64 = 15_000
        public var slopeWindowMs: Int64 = 1000
        public var dropRatioThreshold = 0.05
        /// A new value goes to the encoder only if it differs from the applied one by this ratio.
        public var applyThresholdRatio = 0.05

        public init(initialBps: Int64, minBps: Int64, maxBps: Int64) {
            precondition(minBps >= 1 && minBps <= maxBps && initialBps >= minBps && initialBps <= maxBps)
            self.initialBps = initialBps
            self.minBps = minBps
            self.maxBps = maxBps
        }
    }

    private struct DelayPoint {
        let atMs: Int64
        let c2rAvgUs: Int64
    }

    private static let longAgo = Int64.min / 2

    private let config: Config
    private var target: Double
    public private(set) var appliedBps: Int64
    private var lastDecreaseMs = longAgo
    private var lastCongestionMs = longAgo
    private var lastTickMs: Int64?
    private var startMs: Int64?
    private var queueDelayUs: Int64 = 0
    private var pendingCongestion = false
    private var recoveryTarget = 0.0
    private var falling = false
    private var delayHistory: [DelayPoint] = []

    public init(config: Config) {
        self.config = config
        target = Double(config.initialBps)
        appliedBps = config.initialBps
    }

    public var targetBps: Int64 { Int64(target.rounded()) }

    public func onSenderQueueDelay(_ delayUs: Int64) { queueDelayUs = delayUs }

    /// The sender dropped frames because of a backlog.
    public func onSenderDrop() { pendingCongestion = true }

    public func onReceiverReport(nowMs: Int64, report: ReceiverReport) {
        let c2r = Int64(report.c2rAvgUs)
        delayHistory.append(DelayPoint(atMs: nowMs, c2rAvgUs: c2r))
        while delayHistory.count > 2 && nowMs - delayHistory[1].atMs >= config.slopeWindowMs {
            delayHistory.removeFirst()
        }
        if report.framesReceived > 0,
           Double(report.framesDropped) / Double(report.framesReceived) > config.dropRatioThreshold {
            pendingCongestion = true
        }
        let oldest = delayHistory[0]
        let dt = nowMs - oldest.atMs
        if dt >= config.slopeWindowMs / 2 {
            let slopePerSec = Double(c2r - oldest.c2rAvgUs) * 1000.0 / Double(dt)
            if slopePerSec > Double(config.delaySlopeThresholdUsPerSec) { pendingCongestion = true }
        }
    }

    /// Runs every `tickMs`. Returns a bitrate to give to the encoder, or nil if nothing changes.
    public func tick(nowMs: Int64) -> Int64? {
        if startMs == nil { startMs = nowMs }
        let start = startMs ?? nowMs
        let elapsedTicks = lastTickMs.map { min(max(Double(nowMs - $0) / Double(config.tickMs), 0), 8) } ?? 1
        lastTickMs = nowMs

        let congested = pendingCongestion || queueDelayUs > config.queueDelayThresholdUs
        pendingCongestion = false

        if congested {
            lastCongestionMs = nowMs
            if nowMs - lastDecreaseMs >= config.decreaseHoldMs {
                // A short stall can reach the floor. Remember what worked; congestion during recovery lowers this.
                if !falling { recoveryTarget = target * config.recoveryTargetRatio }
                falling = true
                target = max(Double(config.minBps), target * config.decreaseFactor)
                lastDecreaseMs = nowMs
            }
        } else if nowMs - max(lastCongestionMs, start) >= config.stableMsBeforeIncrease {
            falling = false
            if target < recoveryTarget {
                target = min(recoveryTarget, target * pow(config.recoveryIncreaseFactor, elapsedTicks))
            } else {
                target = min(Double(config.maxBps), target * pow(config.increaseFactor, elapsedTicks))
            }
        }

        let next = Int64(target.rounded())
        let change = Double(abs(next - appliedBps)) / Double(appliedBps)
        let atBound = (next == config.minBps || next == config.maxBps) && next != appliedBps
        guard change >= config.applyThresholdRatio || atBound else { return nil }
        appliedBps = next
        return next
    }

    /// The user changed the preset or the stream restarted.
    public func reset(initialBps: Int64? = nil) {
        let initial = initialBps ?? config.initialBps
        precondition(initial >= config.minBps && initial <= config.maxBps)
        target = Double(initial)
        appliedBps = initial
        lastDecreaseMs = Self.longAgo
        lastCongestionMs = Self.longAgo
        lastTickMs = nil
        startMs = nil
        queueDelayUs = 0
        pendingCongestion = false
        recoveryTarget = 0
        falling = false
        delayHistory.removeAll()
    }
}
