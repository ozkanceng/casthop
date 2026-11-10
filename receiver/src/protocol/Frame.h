#pragma once

#include "protocol/Bytes.h"

#include <cstdint>
#include <optional>

namespace yansit::protocol {

inline constexpr std::uint16_t kMagic = 0x5954;
inline constexpr std::uint8_t kVersion = 1;
inline constexpr std::size_t kHeaderSize = 24;

enum class FrameType : std::uint8_t { Video = 0x01, Audio = 0x02, Control = 0x03 };

namespace FrameFlags {
inline constexpr std::uint16_t Config = 0x0001;
inline constexpr std::uint16_t Keyframe = 0x0002;
inline constexpr std::uint16_t Discontinuity = 0x0004;
/// VIDEO only: the picture is turned 90° clockwise (top edge on the frame's right); the receiver turns it back.
inline constexpr std::uint16_t Rotated = 0x0008;
} // namespace FrameFlags

/// Türe göre en büyük yük (PROTOCOL.md §3).
std::uint32_t maxPayload(FrameType type);

struct FrameHeader {
    FrameType type = FrameType::Control;
    std::uint16_t flags = 0;
    std::uint64_t ptsUs = 0;
    std::uint32_t seq = 0;
    std::uint32_t length = 0;

    bool isConfig() const { return flags & FrameFlags::Config; }
    bool isKeyframe() const { return flags & FrameFlags::Keyframe; }
    bool isDiscontinuity() const { return flags & FrameFlags::Discontinuity; }
    bool isRotated() const { return flags & FrameFlags::Rotated; }
};

/// En az 24 bayt bekler. @throws ProtocolException
FrameHeader decodeHeader(ByteView data);

void encodeHeader(const FrameHeader& header, Bytes& out);

Bytes encodeFrame(FrameType type, std::uint16_t flags, std::uint64_t ptsUs, std::uint32_t seq, ByteView payload);

/// Bayt akışından artımlı çerçeve ayrıştırıcı (TCP okumaları rastgele parçalarda gelir).
class FrameParser {
public:
    struct Frame {
        FrameHeader header;
        Bytes payload;
    };

    /// Yeni veriyi ekler. @throws ProtocolException geçersiz başlıkta (ardından nesne kullanılmamalı).
    void feed(ByteView data);

    /// Tamamlanmış bir çerçeve varsa çıkarır.
    std::optional<Frame> next();

    /// Henüz çerçeveye dönüşmemiş bayt sayısı.
    std::size_t buffered() const { return m_buffer.size() - m_offset; }

private:
    Bytes m_buffer;
    std::size_t m_offset = 0;
    std::optional<FrameHeader> m_pending;
};

} // namespace yansit::protocol
