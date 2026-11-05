import Foundation

/// Monotonic microseconds. CLOCK_UPTIME_RAW is the host clock of mach_absolute_time, the same clock as
/// CMClockGetHostTimeClock and AVAudioTime, so capture stamps need no conversion (PROTOCOL.md §1).
public enum MonotonicClock {
    public static func nowUs() -> UInt64 { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000 }
}

/// Clock offset from PING/PONG (PROTOCOL.md §7.4). The sample with the lowest RTT of the last `window` wins: asymmetric
/// queueing affects it least. `offset = responder clock − initiator clock` (µs).
public struct ClockSync {
    public struct Sample: Equatable, Sendable {
        public let offsetUs: Int64
        public let rttUs: Int64
    }

    private let window: Int
    private var samples: [Sample] = []

    public init(window: Int = 8) { self.window = window }

    /// The accepted sample, or nil if the RTT is negative (bad stamps).
    @discardableResult
    public mutating func onPong(t0Us: Int64, t1Us: Int64, t2Us: Int64, t3Us: Int64) -> Sample? {
        let rtt = (t3Us - t0Us) - (t2Us - t1Us)
        if rtt < 0 { return nil }
        // Halve before adding to avoid overflow.
        let sample = Sample(offsetUs: (t1Us - t0Us) / 2 + (t2Us - t3Us) / 2, rttUs: rtt)
        if samples.count == window { samples.removeFirst() }
        samples.append(sample)
        return sample
    }

    public var best: Sample? { samples.min { $0.rttUs < $1.rttUs } }
    public var offsetUs: Int64? { best?.offsetUs }
    public var rttUs: Int64? { best?.rttUs }

    public mutating func reset() { samples.removeAll() }
}
