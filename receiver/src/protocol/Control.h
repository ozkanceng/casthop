#pragma once

#include "protocol/Bytes.h"

#include <cstdint>
#include <optional>
#include <string>
#include <variant>
#include <vector>

namespace yansit::protocol {

namespace MsgType {
inline constexpr std::uint8_t Challenge = 0x00;
inline constexpr std::uint8_t Hello = 0x01;
inline constexpr std::uint8_t Welcome = 0x02;
inline constexpr std::uint8_t Reject = 0x03;
inline constexpr std::uint8_t PendingApproval = 0x04;
inline constexpr std::uint8_t StreamConfig = 0x10;
inline constexpr std::uint8_t KeyframeRequest = 0x11;
inline constexpr std::uint8_t Ping = 0x20;
inline constexpr std::uint8_t Pong = 0x21;
inline constexpr std::uint8_t ReceiverReport = 0x30;
inline constexpr std::uint8_t SenderStats = 0x31;
inline constexpr std::uint8_t Pause = 0x40;
inline constexpr std::uint8_t Unpause = 0x41;
inline constexpr std::uint8_t Attach = 0x50;
inline constexpr std::uint8_t AttachResult = 0x51;
inline constexpr std::uint8_t Resume = 0x60;
inline constexpr std::uint8_t Bye = 0x70;
inline constexpr std::uint8_t FlagCritical = 0x01;
} // namespace MsgType

namespace Codec {
inline constexpr std::uint8_t H264 = 0x01;
inline constexpr std::uint8_t H265 = 0x02;
inline constexpr std::uint8_t Opus = 0x10;
inline constexpr std::uint8_t AacLc = 0x11;
} // namespace Codec

namespace AuthMethod {
inline constexpr std::uint8_t QrToken = 1;
inline constexpr std::uint8_t Pin = 2;
inline constexpr std::uint8_t Paired = 3;
} // namespace AuthMethod

namespace StreamKind {
inline constexpr std::uint8_t Video = 1;
inline constexpr std::uint8_t Audio = 2;
} // namespace StreamKind

namespace RejectReason {
inline constexpr std::uint8_t VersionUnsupported = 1;
inline constexpr std::uint8_t AuthFailed = 2;
inline constexpr std::uint8_t BusyDenied = 3;
inline constexpr std::uint8_t BusyTimeout = 4;
inline constexpr std::uint8_t RateLimited = 5;
inline constexpr std::uint8_t Malformed = 6;
inline constexpr std::uint8_t Internal = 7;
inline constexpr std::uint8_t SessionUnknown = 8;
inline constexpr std::uint8_t LockedOut = 9;
} // namespace RejectReason

namespace KeyframeReason {
inline constexpr std::uint8_t DecoderReset = 1;
inline constexpr std::uint8_t BacklogDropped = 2;
inline constexpr std::uint8_t DecodeError = 3;
inline constexpr std::uint8_t User = 4;
} // namespace KeyframeReason

namespace ByeReason {
inline constexpr std::uint8_t UserStop = 1;
inline constexpr std::uint8_t ProjectionStopped = 2;
inline constexpr std::uint8_t Takeover = 3;
inline constexpr std::uint8_t ReceiverClosing = 4;
inline constexpr std::uint8_t Error = 5;
inline constexpr std::uint8_t Timeout = 6;
inline constexpr std::uint8_t Malformed = 7;
} // namespace ByeReason

/// Çözücü desteği: flags bit0 = donanım, bit1 = tercih edilen.
struct CodecSupport {
    static constexpr std::uint8_t Hardware = 0x01;
    static constexpr std::uint8_t Preferred = 0x02;
    std::uint8_t codec = 0;
    std::uint8_t flags = 0;
    bool operator==(const CodecSupport&) const = default;
};

struct Challenge {
    std::uint16_t minVersion = 1;
    std::uint16_t maxVersion = 1;
    Id16 serverNonce{};
    std::optional<std::string> receiverName;
    bool operator==(const Challenge&) const = default;
};

struct Hello {
    std::uint16_t version = 1;
    std::string deviceName;
    Id16 deviceId{};
    std::string appVersion;
    std::uint8_t authMethod = 0;
    Id16 clientNonce{};
    Key32 authProof{};
    std::optional<Id16> keyId;
    std::vector<std::uint8_t> videoCodecs;
    std::optional<std::vector<std::uint8_t>> audioCodecs;
    std::uint16_t maxWidth = 0;
    std::uint16_t maxHeight = 0;
    std::uint8_t maxFps = 0;
    std::optional<bool> requestPairing;
    bool operator==(const Hello&) const = default;
};

struct Welcome {
    std::uint16_t version = 1;
    std::string receiverName;
    Id16 sessionId{};
    std::uint16_t displayWidth = 0;
    std::uint16_t displayHeight = 0;
    std::uint8_t displayRefresh = 0;
    std::vector<CodecSupport> videoDecoders;
    std::vector<CodecSupport> audioDecoders;
    Key32 attachKey{};
    Key32 resumeToken{};
    std::optional<Key32> pairKey;
    std::optional<Id16> keyId;
    bool audioEnabled = false;
    /// The receiver turns FrameFlags::Rotated frames back; written only when true.
    bool rotatedFrames = false;
    bool operator==(const Welcome&) const = default;
};

struct Reject {
    std::uint8_t reason = 0;
    std::optional<std::uint32_t> retryAfterMs;
    bool operator==(const Reject&) const = default;
};

struct PendingApproval {
    std::uint32_t timeoutMs = 0;
    bool operator==(const PendingApproval&) const = default;
};

struct StreamConfig {
    std::uint8_t streamKind = 0;
    std::uint8_t codec = 0;
    std::optional<std::uint16_t> width;
    std::optional<std::uint16_t> height;
    std::optional<std::uint8_t> fps;
    std::optional<std::uint32_t> bitrateBps;
    std::optional<std::uint32_t> sampleRate;
    std::optional<std::uint8_t> channels;
    std::optional<std::uint16_t> rotationDeg;
    bool operator==(const StreamConfig&) const = default;
};

struct KeyframeRequest {
    std::uint8_t reason = 0;
    bool operator==(const KeyframeRequest&) const = default;
};

struct Ping {
    std::uint32_t pingId = 0;
    std::uint64_t t0Us = 0;
    std::optional<std::int64_t> offsetUs;
    bool operator==(const Ping&) const = default;
};

struct Pong {
    std::uint32_t pingId = 0;
    std::uint64_t t0Us = 0;
    std::uint64_t t1Us = 0;
    std::uint64_t t2Us = 0;
    bool operator==(const Pong&) const = default;
};

struct ReceiverReport {
    std::uint16_t intervalMs = 0;
    std::uint32_t framesReceived = 0;
    std::uint32_t framesRendered = 0;
    std::uint32_t framesDropped = 0;
    std::uint32_t bytesReceived = 0;
    std::uint32_t c2rAvgUs = 0;
    std::uint32_t c2rMinUs = 0;
    std::uint32_t c2rMaxUs = 0;
    std::uint32_t c2dAvgUs = 0;
    std::uint16_t decodeBacklog = 0;
    std::uint16_t audioLate = 0;
    bool operator==(const ReceiverReport&) const = default;
};

struct SenderStats {
    std::uint32_t bitrateBps = 0;
    std::uint16_t fpsX100 = 0;
    std::uint32_t framesDropped = 0;
    std::optional<std::string> encoderName;
    bool operator==(const SenderStats&) const = default;
};

struct Pause {
    bool operator==(const Pause&) const = default;
};
struct Unpause {
    bool operator==(const Unpause&) const = default;
};

struct Attach {
    Id16 sessionId{};
    std::uint8_t streamKind = 0;
    Key32 proof{};
    bool operator==(const Attach&) const = default;
};

struct AttachResult {
    std::uint8_t status = 0;
    bool operator==(const AttachResult&) const = default;
};

struct Resume {
    Id16 sessionId{};
    Key32 proof{};
    bool operator==(const Resume&) const = default;
};

struct Bye {
    std::uint8_t reason = 0;
    bool operator==(const Bye&) const = default;
};

/// Tanınmayan ve CRITICAL olmayan mesaj: yok sayılmalı.
struct UnknownMessage {
    std::uint8_t msgType = 0;
    std::uint8_t msgFlags = 0;
    bool operator==(const UnknownMessage&) const = default;
};

using ControlMessage = std::variant<Challenge, Hello, Welcome, Reject, PendingApproval, StreamConfig, KeyframeRequest,
                                    Ping, Pong, ReceiverReport, SenderStats, Pause, Unpause, Attach, AttachResult,
                                    Resume, Bye, UnknownMessage>;

/// Kanonik (artan etiket sıralı) kodlama. UnknownMessage kodlanamaz (std::invalid_argument).
Bytes encodeControl(const ControlMessage& msg);

/// @throws ProtocolException geçersiz yükte.
ControlMessage decodeControl(ByteView payload);

inline constexpr std::size_t kMaxStrBytes = 255;

} // namespace yansit::protocol
