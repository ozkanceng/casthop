import AVFoundation
import AudioToolbox
import CastHopProtocol
import Foundation

/// Decodes one compressed packet at a time to Float32 with an AudioConverter.
final class PacketDecoder {
    private final class Input {
        var data: UnsafeRawPointer?
        var size: UInt32 = 0
        var channels: UInt32 = 0
        var consumed = true
        let description = UnsafeMutablePointer<AudioStreamPacketDescription>.allocate(capacity: 1)

        deinit { description.deallocate() }
    }

    private static let noMoreInput: OSStatus = 0x6E6D6F72 // 'nmor'
    /// The longest Opus packet.
    private static let maxFrames: AVAudioFrameCount = 5760

    let format: AVAudioFormat
    private var converter: AudioConverterRef?
    private let input = Input()

    /// `sampleRate` and `channels` come from the codec's CONFIG.
    init?(formatId: AudioFormatID, sampleRate: Double, channels: UInt32, framesPerPacket: UInt32, cookie: Data?) {
        guard let out = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: channels) else { return nil }
        format = out
        var source = AudioStreamBasicDescription(mSampleRate: sampleRate, mFormatID: formatId, mFormatFlags: 0, mBytesPerPacket: 0,
                                                 mFramesPerPacket: framesPerPacket, mBytesPerFrame: 0,
                                                 mChannelsPerFrame: channels, mBitsPerChannel: 0, mReserved: 0)
        var made: AudioConverterRef?
        guard AudioConverterNew(&source, out.streamDescription, &made) == noErr, let made else { return nil }
        converter = made
        input.channels = channels
        if let cookie {
            cookie.withUnsafeBytes { raw in
                // The decoders tolerate a missing cookie, so a refusal here is not an error.
                _ = AudioConverterSetProperty(made, kAudioConverterDecompressionMagicCookie, UInt32(raw.count), raw.baseAddress!)
            }
        }
    }

    deinit {
        if let converter { AudioConverterDispose(converter) }
    }

    static func aac(config: Data) -> PacketDecoder? {
        guard let asc = AudioSpecificConfig.parse(config), asc.objectType == AudioSpecificConfig.aacLc else { return nil }
        return PacketDecoder(formatId: kAudioFormatMPEG4AAC, sampleRate: Double(asc.sampleRate), channels: UInt32(asc.channels),
                             framesPerPacket: 1024, cookie: nil)
    }

    /// `head` is an OpusHead structure (RFC 7845 §5.1).
    static func opus(head: Data) -> PacketDecoder? {
        let bytes = [UInt8](head)
        guard bytes.count >= 19, bytes.starts(with: Array("OpusHead".utf8)), bytes[9] >= 1, bytes[9] <= 2 else { return nil }
        return PacketDecoder(formatId: kAudioFormatOpus, sampleRate: 48_000, channels: UInt32(bytes[9]), framesPerPacket: 960,
                             cookie: head)
    }

    /// Nil if the codec gave no audio for this packet, as a decoder may while it primes.
    func decode(_ packet: Data) -> AVAudioPCMBuffer? {
        guard let converter, !packet.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: Self.maxFrames) else { return nil }
        var frames = UInt32(Self.maxFrames)
        let status: OSStatus = packet.withUnsafeBytes { raw in
            input.data = raw.baseAddress
            input.size = UInt32(raw.count)
            input.consumed = false
            return AudioConverterFillComplexBuffer(converter, { _, count, data, description, user in
                let input = Unmanaged<Input>.fromOpaque(user!).takeUnretainedValue()
                if input.consumed {
                    count.pointee = 0
                    return PacketDecoder.noMoreInput
                }
                input.consumed = true
                count.pointee = 1
                data.pointee.mNumberBuffers = 1
                data.pointee.mBuffers.mData = UnsafeMutableRawPointer(mutating: input.data)
                data.pointee.mBuffers.mDataByteSize = input.size
                data.pointee.mBuffers.mNumberChannels = input.channels
                input.description.pointee = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0,
                                                                         mDataByteSize: input.size)
                description?.pointee = input.description
                return noErr
            }, Unmanaged.passUnretained(input).toOpaque(), &frames, buffer.mutableAudioBufferList, nil)
        }
        input.data = nil
        guard status == noErr || status == Self.noMoreInput, frames > 0 else { return nil }
        buffer.frameLength = frames
        return buffer
    }
}

/// Audio receive path (PROTOCOL.md §11). Runs on the receiver queue. Without a device (`headless`) packets are decoded and
/// counted but not played.
public final class AudioPlayer: @unchecked Sendable {
    public struct Stats: Sendable {
        public var late: UInt32 = 0
        public var latencySumUs: UInt64 = 0
        public var latencyCount: UInt64 = 0
    }

    public static let minTargetUs: UInt64 = 40_000
    public static let maxTargetUs: UInt64 = 250_000
    static let lateMarginUs: Int64 = 5_000
    static let arrivalWindow = 250
    static let arrivalMarginUs: UInt64 = 10_000
    /// The schedule may slip by this much before it is anchored again.
    static let maxDriftUs: Int64 = 40_000

    /// Whether this system's AudioToolbox has an Opus decoder.
    public static let opusAvailable: Bool = {
        var head = Data("OpusHead".utf8)
        head.append(contentsOf: [1, 2, 0, 0, 0x80, 0xBB, 0, 0, 0, 0, 0])
        return PacketDecoder.opus(head: head) != nil
    }()

    public static let aacAvailable: Bool = {
        PacketDecoder.aac(config: AudioSpecificConfig(sampleRate: 48_000, channels: 2).encoded()) != nil
    }()

    private let headless: Bool
    private var decoder: PacketDecoder?
    private var offsetUs: Int64?
    private var targetUs = AudioPlayer.minTargetUs
    private var arrivals: [UInt32] = []
    private var stats = Stats()
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var configurationObserver: NSObjectProtocol?
    /// When the audio scheduled so far ends, on the local clock. 0 means anchor at the next packet.
    private var scheduledEndUs: Int64 = 0

    public private(set) var totalReceived: Int64 = 0
    public private(set) var totalPlayed: Int64 = 0
    public private(set) var totalLate: Int64 = 0

    public init(headless: Bool) { self.headless = headless }

    deinit { teardown() }

    public func setClockOffset(_ offset: Int64?) { offsetUs = offset }

    public func takeStats() -> Stats {
        defer { stats = Stats() }
        return stats
    }

    /// Lip sync: audio plays when the picture reaches the screen. It also needs enough time for its own jitter.
    public func setTargetLatency(videoUs: UInt64) {
        var need: UInt64 = 0
        if arrivals.count >= 10 {
            let sorted = arrivals.sorted()
            need = UInt64(sorted[sorted.count * 95 / 100]) + Self.arrivalMarginUs
        }
        targetUs = min(max(max(videoUs, need), Self.minTargetUs), Self.maxTargetUs)
    }

    public func reset() {
        teardown()
        decoder = nil
        offsetUs = nil
        targetUs = Self.minTargetUs
        arrivals = []
        stats = Stats()
        totalReceived = 0
        totalPlayed = 0
        totalLate = 0
    }

    public func stop() { teardown() }

    private func teardown() {
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        configurationObserver = nil
        player?.stop()
        engine?.stop()
        player = nil
        engine = nil
        scheduledEndUs = 0
    }

    public func push(header: FrameHeader, payload: Data, recvUs: UInt64) {
        if header.isConfig {
            configure(payload)
            return
        }
        totalReceived += 1
        guard let decoder, let offsetUs else { return }

        let captureLocal = Int64(header.ptsUs) + offsetUs
        let arrival = Int64(recvUs) - captureLocal
        arrivals.append(UInt32(clamping: max(arrival, 0)))
        if arrivals.count > Self.arrivalWindow { arrivals.removeFirst() }

        let playAt = captureLocal + Int64(targetUs)
        if playAt < Int64(recvUs) + Self.lateMarginUs {
            totalLate += 1
            stats.late += 1
            return
        }
        guard let buffer = decoder.decode(payload) else { return }
        if !headless { schedule(buffer, playAtUs: playAt) }
        totalPlayed += 1
        stats.latencySumUs += targetUs
        stats.latencyCount += 1
    }

    private func configure(_ payload: Data) {
        guard let codec = payload.first else { return }
        let config = Data(payload.dropFirst())
        teardown()
        switch codec {
        case Codec.aacLc: decoder = PacketDecoder.aac(config: config)
        case Codec.opus: decoder = PacketDecoder.opus(head: config)
        default: decoder = nil
        }
        if let decoder, !headless { startEngine(decoder.format) }
    }

    private func startEngine(_ format: AVAudioFormat) {
#if !os(macOS)
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.setCategory(.playback, mode: .default)
        try? audioSession.setActive(true)
#endif
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            return // no output device: packets are still decoded and counted
        }
        player.play()
        self.engine = engine
        self.player = player
        // A route change stops the engine.
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self, weak engine, weak player] _ in
            try? engine?.start()
            player?.play()
            self?.scheduledEndUs = 0
        }
    }

    private func schedule(_ buffer: AVAudioPCMBuffer, playAtUs: Int64) {
        guard let player else { return }
        let durationUs = Int64(Double(buffer.frameLength) / buffer.format.sampleRate * 1e6)
        if scheduledEndUs == 0 || abs(playAtUs - scheduledEndUs) > Self.maxDriftUs {
            if scheduledEndUs != 0 {
                player.stop()
                player.play()
            }
            let host = AVAudioTime.hostTime(forSeconds: Double(playAtUs) / 1e6)
            player.scheduleBuffer(buffer, at: AVAudioTime(hostTime: host), options: [], completionHandler: nil)
            scheduledEndUs = playAtUs + durationUs
        } else {
            player.scheduleBuffer(buffer, at: nil, options: [], completionHandler: nil)
            scheduledEndUs += durationUs
        }
    }
}
