#include "protocol/Frame.h"

#include "protocol/Errors.h"

#include <string>

namespace yansit::protocol {

std::uint32_t maxPayload(FrameType type) {
    switch (type) {
    case FrameType::Video:
        return 8u * 1024 * 1024;
    case FrameType::Audio:
    case FrameType::Control:
        return 64u * 1024;
    }
    return 0;
}

FrameHeader decodeHeader(ByteView data) {
    if (data.size() < kHeaderSize)
        throw ProtocolException(ProtocolError::Truncated, "başlık");
    const std::uint8_t* p = data.data();
    if (getU16(p) != kMagic)
        throw ProtocolException(ProtocolError::BadMagic);
    if (p[2] != kVersion)
        throw ProtocolException(ProtocolError::UnsupportedVersion, "v" + std::to_string(p[2]));
    const std::uint8_t type = p[3];
    if (type < 0x01 || type > 0x03)
        throw ProtocolException(ProtocolError::UnknownFrameType, std::to_string(type));

    FrameHeader h;
    h.type = static_cast<FrameType>(type);
    h.flags = getU16(p + 4);
    // p[6..7] ayrılmış: yok sayılır.
    h.ptsUs = getU64(p + 8);
    h.seq = getU32(p + 16);
    h.length = getU32(p + 20);
    if (h.length > maxPayload(h.type))
        throw ProtocolException(ProtocolError::FrameTooLarge, std::to_string(h.length));
    return h;
}

void encodeHeader(const FrameHeader& h, Bytes& out) {
    putU16(out, kMagic);
    out.push_back(kVersion);
    out.push_back(static_cast<std::uint8_t>(h.type));
    putU16(out, h.flags);
    putU16(out, 0);
    putU64(out, h.ptsUs);
    putU32(out, h.seq);
    putU32(out, h.length);
}

Bytes encodeFrame(FrameType type, std::uint16_t flags, std::uint64_t ptsUs, std::uint32_t seq, ByteView payload) {
    if (payload.size() > maxPayload(type))
        throw std::invalid_argument("yük sınırı aşıldı");
    Bytes out;
    out.reserve(kHeaderSize + payload.size());
    encodeHeader({type, flags, ptsUs, seq, static_cast<std::uint32_t>(payload.size())}, out);
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

void FrameParser::feed(ByteView data) {
    // Tüketilmiş baytları ara sıra at; tampon sınırsız büyümesin.
    if (m_offset > 0 && m_offset >= m_buffer.size() / 2) {
        m_buffer.erase(m_buffer.begin(), m_buffer.begin() + static_cast<std::ptrdiff_t>(m_offset));
        m_offset = 0;
    }
    m_buffer.insert(m_buffer.end(), data.begin(), data.end());
}

std::optional<FrameParser::Frame> FrameParser::next() {
    if (!m_pending) {
        if (buffered() < kHeaderSize)
            return std::nullopt;
        m_pending = decodeHeader(ByteView(m_buffer).subspan(m_offset, kHeaderSize));
        m_offset += kHeaderSize;
    }
    if (buffered() < m_pending->length)
        return std::nullopt;
    Frame f{*m_pending, Bytes(m_buffer.begin() + static_cast<std::ptrdiff_t>(m_offset),
                              m_buffer.begin() + static_cast<std::ptrdiff_t>(m_offset + m_pending->length))};
    m_offset += m_pending->length;
    m_pending.reset();
    return f;
}

} // namespace yansit::protocol
