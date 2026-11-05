import AudioToolbox
import CastHopProtocol
import CoreMedia
import Foundation

public enum AudioEncoderError: Error, Equatable {
    case converter(OSStatus)
}

/// PCM to AAC-LC with an AudioConverter (PROTOCOL.md §6): 1024 samples per raw access unit, no ADTS. App audio from
/// ReplayKit changes format now and then, so the converter follows the input format. Output is always 48 kHz stereo.
public final class AacEncoder: AudioSource, @unchecked Sendable {
    public static let outputRate = 48_000
    public static let outputChannels = 2
    private static let bitrate: UInt32 = 128_000
    private static let framesPerPacket = 1024
    /// The AAC encoder's delay: a decoder gives a sample back this much after the packet that carries it.
    private static let codecDelayUs: Int64 = 2112 * 1_000_000 / 48_000
    /// Half a second at most waits for the encoder; older PCM goes.
    private static let maxPendingSeconds = 0.5
    private static let needMoreInput: OSStatus = 0x6E656564 // 'need'

    public let codec = Codec.aacLc
    public let sampleRate = AacEncoder.outputRate
    public let channels = AacEncoder.outputChannels
    public let config = AudioSpecificConfig(sampleRate: AacEncoder.outputRate, channels: AacEncoder.outputChannels).encoded()

    private let lock = NSLock()
    private var sink: ((AudioPacket) -> Void)?
    private var errorHandler: ((Error) -> Void)?
    private var converter: AudioConverterRef?
    private var inputFormat = AudioStreamBasicDescription()
    /// Pending PCM, one entry per channel buffer: one if interleaved.
    private var pending: [Data] = []
    private var pendingStartUs: Int64 = 0
    private var handed: [UnsafeMutableRawPointer] = []
    private let outputCapacity = 4096
    private let output: UnsafeMutableRawPointer
    private var running = false

    public init() { output = UnsafeMutableRawPointer.allocate(byteCount: 4096, alignment: 16) }

    deinit {
        if let converter { AudioConverterDispose(converter) }
        handed.forEach { $0.deallocate() }
        output.deallocate()
    }

    public func start(sink: @escaping (AudioPacket) -> Void, onError: @escaping (Error) -> Void) throws {
        lock.lock()
        defer { lock.unlock() }
        self.sink = sink
        errorHandler = onError
        running = true
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }
        running = false
        sink = nil
        errorHandler = nil
        pending = []
    }

    /// Takes a PCM sample buffer from ReplayKit or a file reader.
    public func push(_ sample: CMSampleBuffer) {
        guard let description = CMSampleBufferGetFormatDescription(sample),
              let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee else { return }
        var size = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sample, bufferListSizeNeededOut: &size, bufferListOut: nil,
                                                                bufferListSize: 0, blockBufferAllocator: nil,
                                                                blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
        let list = UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 16)
        defer { list.deallocate() }
        let audio = list.bindMemory(to: AudioBufferList.self, capacity: 1)
        var block: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sample, bufferListSizeNeededOut: nil, bufferListOut: audio, bufferListSize: size, blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil, flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &block) == noErr else { return }
        let time = CMSampleBufferGetPresentationTimeStamp(sample)
        let ptsUs = time.isValid ? Int64(CMTimeGetSeconds(time) * 1e6) : Int64(MonotonicClock.nowUs())
        let buffers = UnsafeMutableAudioBufferListPointer(audio).map { b in
            b.mData.map { Data(bytes: $0, count: Int(b.mDataByteSize)) } ?? Data()
        }
        push(pcm: buffers, format: basic, ptsUs: ptsUs)
    }

    /// `pcm` holds one buffer if interleaved, otherwise one per channel.
    public func push(pcm: [Data], format: AudioStreamBasicDescription, ptsUs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard running, format.mBytesPerFrame > 0 else { return }
        if converter == nil || !Self.same(format, inputFormat) {
            guard rebuild(format) else { return }
        }
        if pending.count != pcm.count { pending = pcm.map { _ in Data() } }
        let bytesPerFrame = Int(format.mBytesPerFrame)
        if pending[0].isEmpty { pendingStartUs = ptsUs }
        for i in pcm.indices { pending[i].append(pcm[i]) }

        let maxBytes = Int(format.mSampleRate * Self.maxPendingSeconds) * bytesPerFrame
        if pending[0].count > maxBytes {
            let drop = (pending[0].count - maxBytes) / bytesPerFrame * bytesPerFrame
            for i in pending.indices { pending[i] = Data(pending[i].dropFirst(drop)) }
            pendingStartUs += Int64(Double(drop / bytesPerFrame) / format.mSampleRate * 1e6)
        }
        drain()
    }

    private static func same(_ a: AudioStreamBasicDescription, _ b: AudioStreamBasicDescription) -> Bool {
        a.mSampleRate == b.mSampleRate && a.mFormatID == b.mFormatID && a.mFormatFlags == b.mFormatFlags
            && a.mBytesPerFrame == b.mBytesPerFrame && a.mChannelsPerFrame == b.mChannelsPerFrame
    }

    private func rebuild(_ format: AudioStreamBasicDescription) -> Bool {
        if let converter { AudioConverterDispose(converter) }
        converter = nil
        pending = []
        var source = format
        var target = AudioStreamBasicDescription(mSampleRate: Double(Self.outputRate), mFormatID: kAudioFormatMPEG4AAC,
                                                 mFormatFlags: 0, mBytesPerPacket: 0,
                                                 mFramesPerPacket: UInt32(Self.framesPerPacket), mBytesPerFrame: 0,
                                                 mChannelsPerFrame: UInt32(Self.outputChannels), mBitsPerChannel: 0, mReserved: 0)
        var made: AudioConverterRef?
        let status = AudioConverterNew(&source, &target, &made)
        guard status == noErr, let made else {
            errorHandler?(AudioEncoderError.converter(status))
            return false
        }
        var rate = Self.bitrate
        AudioConverterSetProperty(made, kAudioConverterEncodeBitRate, UInt32(MemoryLayout<UInt32>.size), &rate)
        converter = made
        inputFormat = format
        return true
    }

    /// Takes packets while the converter can make them. The lock is held by the caller.
    private func drain() {
        guard let converter else { return }
        while true {
            var packets: UInt32 = 1
            var description = AudioStreamPacketDescription()
            var list = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(mNumberChannels: UInt32(Self.outputChannels),
                                                                                mDataByteSize: UInt32(outputCapacity), mData: output))
            let status = AudioConverterFillComplexBuffer(converter, { _, count, data, _, user in
                Unmanaged<AacEncoder>.fromOpaque(user!).takeUnretainedValue().provide(count: count, data: data)
            }, Unmanaged.passUnretained(self).toOpaque(), &packets, &list, &description)
            guard status == noErr || status == Self.needMoreInput else {
                errorHandler?(AudioEncoderError.converter(status))
                return
            }
            guard packets > 0, description.mDataByteSize > 0 else { return }
            let bytes = Data(bytes: output + Int(description.mStartOffset), count: Int(description.mDataByteSize))
            sink?(AudioPacket(data: bytes, ptsUs: UInt64(max(pendingStartUs - Self.codecDelayUs, 0))))
            if status == Self.needMoreInput { return }
        }
    }

    /// The converter's input callback: hands over all pending PCM, which lives until the next call.
    private func provide(count: UnsafeMutablePointer<UInt32>, data: UnsafeMutablePointer<AudioBufferList>) -> OSStatus {
        handed.forEach { $0.deallocate() }
        handed = []
        guard let first = pending.first, !first.isEmpty else {
            count.pointee = 0
            return Self.needMoreInput
        }
        let bytesPerFrame = Int(inputFormat.mBytesPerFrame)
        let frames = first.count / bytesPerFrame
        let buffers = UnsafeMutableAudioBufferListPointer(data)
        guard frames > 0, buffers.count == pending.count else {
            count.pointee = 0
            return Self.needMoreInput
        }
        for i in pending.indices {
            let size = frames * bytesPerFrame
            let copy = UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 16)
            pending[i].copyBytes(to: copy.assumingMemoryBound(to: UInt8.self), count: size)
            handed.append(copy)
            buffers[i].mData = copy
            buffers[i].mDataByteSize = UInt32(size)
            buffers[i].mNumberChannels = pending.count == 1 ? inputFormat.mChannelsPerFrame : 1
            pending[i] = Data(pending[i].dropFirst(size))
        }
        pendingStartUs += Int64(Double(frames) / inputFormat.mSampleRate * 1e6)
        count.pointee = UInt32(frames)
        return noErr
    }
}
