#include "protocol/Control.h"

#include "protocol/Errors.h"

#include <array>
#include <cstdio>
#include <stdexcept>

namespace yansit::protocol {

namespace {

std::string tagName(std::uint8_t tag) {
    char buf[8];
    std::snprintf(buf, sizeof buf, "0x%02x", tag);
    return std::string("tag ") + buf;
}

class TlvWriter {
public:
    TlvWriter(std::uint8_t msgType, std::uint8_t msgFlags = 0) {
        m_out.push_back(msgType);
        m_out.push_back(msgFlags);
    }

    void u8(std::uint8_t tag, std::uint8_t v) {
        header(tag, 1);
        m_out.push_back(v);
    }
    void boolean(std::uint8_t tag, bool v) { u8(tag, v ? 1 : 0); }
    void u16(std::uint8_t tag, std::uint16_t v) {
        header(tag, 2);
        putU16(m_out, v);
    }
    void u32(std::uint8_t tag, std::uint32_t v) {
        header(tag, 4);
        putU32(m_out, v);
    }
    void u64(std::uint8_t tag, std::uint64_t v) {
        header(tag, 8);
        putU64(m_out, v);
    }
    void raw(std::uint8_t tag, ByteView v) {
        if (v.size() > 0xFFFF)
            throw std::invalid_argument("TLV değeri çok uzun");
        header(tag, static_cast<std::uint16_t>(v.size()));
        m_out.insert(m_out.end(), v.begin(), v.end());
    }
    void str(std::uint8_t tag, const std::string& s) {
        if (s.size() > kMaxStrBytes)
            throw std::invalid_argument("dizi 255 baytı aşıyor");
        raw(tag, ByteView(reinterpret_cast<const std::uint8_t*>(s.data()), s.size()));
    }
    void pairs(std::uint8_t tag, const std::vector<CodecSupport>& v) {
        Bytes b;
        for (const auto& p : v) {
            b.push_back(p.codec);
            b.push_back(p.flags);
        }
        raw(tag, b);
    }

    Bytes take() { return std::move(m_out); }

private:
    void header(std::uint8_t tag, std::uint16_t len) {
        if (int(tag) <= m_lastTag)
            throw std::logic_error("TLV etiketleri artan sırada olmalı");
        m_lastTag = tag;
        m_out.push_back(tag);
        putU16(m_out, len);
    }

    Bytes m_out;
    int m_lastTag = -1;
};

bool validUtf8(ByteView s) {
    std::size_t i = 0;
    while (i < s.size()) {
        const std::uint8_t c = s[i];
        std::size_t n;
        std::uint32_t cp;
        if (c < 0x80) {
            ++i;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            n = 1;
            cp = c & 0x1F;
        } else if ((c & 0xF0) == 0xE0) {
            n = 2;
            cp = c & 0x0F;
        } else if ((c & 0xF8) == 0xF0) {
            n = 3;
            cp = c & 0x07;
        } else {
            return false;
        }
        for (std::size_t k = 1; k <= n; ++k) {
            if (i + k >= s.size() || (s[i + k] & 0xC0) != 0x80)
                return false;
            cp = (cp << 6) | (s[i + k] & 0x3F);
        }
        // Aşırı uzun kodlama, vekil çiftler ve aralık dışı reddedilir (Java'nın REPORT davranışı).
        static constexpr std::uint32_t minCp[] = {0, 0x80, 0x800, 0x10000};
        if (cp < minCp[n] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))
            return false;
        i += n + 1;
    }
    return true;
}

class TlvReader {
public:
    explicit TlvReader(ByteView payload) {
        if (payload.empty())
            throw ProtocolException(ProtocolError::EmptyControl);
        if (payload.size() < 2)
            throw ProtocolException(ProtocolError::Truncated, "msg_flags eksik");
        msgType = payload[0];
        msgFlags = payload[1];
        std::size_t o = 2;
        while (o < payload.size()) {
            if (payload.size() - o < 3)
                throw ProtocolException(ProtocolError::MalformedTlv, "yarım TLV başlığı");
            const std::uint8_t tag = payload[o];
            const std::uint16_t len = getU16(payload.data() + o + 1);
            o += 3;
            if (payload.size() - o < len)
                throw ProtocolException(ProtocolError::MalformedTlv, tagName(tag) + " taşıyor");
            if (m_present[tag])
                throw ProtocolException(ProtocolError::DuplicateTag, tagName(tag));
            m_present[tag] = true;
            m_fields[tag] = payload.subspan(o, len);
            o += len;
        }
    }

    std::uint8_t msgType = 0;
    std::uint8_t msgFlags = 0;

    std::optional<ByteView> raw(std::uint8_t tag, bool required) const {
        if (!m_present[tag]) {
            if (required)
                throw ProtocolException(ProtocolError::MissingTag, tagName(tag));
            return std::nullopt;
        }
        return m_fields[tag];
    }

    std::optional<ByteView> fixed(std::uint8_t tag, std::size_t size, bool required) const {
        auto v = raw(tag, required);
        if (v && v->size() != size)
            throw ProtocolException(ProtocolError::BadFieldLength, tagName(tag));
        return v;
    }

    std::optional<std::uint8_t> u8(std::uint8_t tag, bool required) const {
        auto v = fixed(tag, 1, required);
        return v ? std::optional<std::uint8_t>((*v)[0]) : std::nullopt;
    }
    std::uint8_t u8(std::uint8_t tag) const { return *u8(tag, true); }
    std::optional<bool> boolean(std::uint8_t tag, bool required) const {
        auto v = u8(tag, required);
        return v ? std::optional<bool>(*v != 0) : std::nullopt;
    }
    std::optional<std::uint16_t> u16(std::uint8_t tag, bool required) const {
        auto v = fixed(tag, 2, required);
        return v ? std::optional<std::uint16_t>(getU16(v->data())) : std::nullopt;
    }
    std::uint16_t u16(std::uint8_t tag) const { return *u16(tag, true); }
    std::optional<std::uint32_t> u32(std::uint8_t tag, bool required) const {
        auto v = fixed(tag, 4, required);
        return v ? std::optional<std::uint32_t>(getU32(v->data())) : std::nullopt;
    }
    std::uint32_t u32(std::uint8_t tag) const { return *u32(tag, true); }
    std::optional<std::uint64_t> u64(std::uint8_t tag, bool required) const {
        auto v = fixed(tag, 8, required);
        return v ? std::optional<std::uint64_t>(getU64(v->data())) : std::nullopt;
    }
    std::uint64_t u64(std::uint8_t tag) const { return *u64(tag, true); }
    template <std::size_t N>
    std::optional<std::array<std::uint8_t, N>> arr(std::uint8_t tag, bool required) const {
        auto v = fixed(tag, N, required);
        return v ? std::optional<std::array<std::uint8_t, N>>(toArray<N>(*v)) : std::nullopt;
    }
    template <std::size_t N>
    std::array<std::uint8_t, N> arr(std::uint8_t tag) const {
        return *arr<N>(tag, true);
    }
    std::optional<std::string> str(std::uint8_t tag, bool required) const {
        auto v = raw(tag, required);
        if (!v)
            return std::nullopt;
        if (v->size() > kMaxStrBytes)
            throw ProtocolException(ProtocolError::StringTooLong, tagName(tag));
        if (!validUtf8(*v))
            throw ProtocolException(ProtocolError::BadUtf8, tagName(tag));
        return std::string(v->begin(), v->end());
    }
    std::string str(std::uint8_t tag) const { return *str(tag, true); }
    std::optional<std::vector<std::uint8_t>> list(std::uint8_t tag, bool required) const {
        auto v = raw(tag, required);
        return v ? std::optional<std::vector<std::uint8_t>>(std::vector<std::uint8_t>(v->begin(), v->end()))
                 : std::nullopt;
    }
    std::vector<CodecSupport> pairs(std::uint8_t tag) const {
        auto v = *raw(tag, true);
        if (v.size() % 2 != 0)
            throw ProtocolException(ProtocolError::BadFieldLength, tagName(tag) + ": tek uzunluk");
        std::vector<CodecSupport> out;
        for (std::size_t i = 0; i < v.size(); i += 2)
            out.push_back({v[i], v[i + 1]});
        return out;
    }

private:
    std::array<bool, 256> m_present{};
    std::array<ByteView, 256> m_fields{};
};

struct Encoder {
    Bytes operator()(const Challenge& m) const {
        TlvWriter w(MsgType::Challenge);
        w.u16(0x01, m.minVersion);
        w.u16(0x02, m.maxVersion);
        w.raw(0x03, m.serverNonce);
        if (m.receiverName)
            w.str(0x04, *m.receiverName);
        return w.take();
    }
    Bytes operator()(const Hello& m) const {
        TlvWriter w(MsgType::Hello);
        w.u16(0x01, m.version);
        w.str(0x02, m.deviceName);
        w.raw(0x03, m.deviceId);
        w.str(0x04, m.appVersion);
        w.u8(0x05, m.authMethod);
        w.raw(0x06, m.clientNonce);
        w.raw(0x07, m.authProof);
        if (m.keyId)
            w.raw(0x08, *m.keyId);
        w.raw(0x09, m.videoCodecs);
        if (m.audioCodecs)
            w.raw(0x0A, *m.audioCodecs);
        w.u16(0x0B, m.maxWidth);
        w.u16(0x0C, m.maxHeight);
        w.u8(0x0D, m.maxFps);
        if (m.requestPairing)
            w.boolean(0x0E, *m.requestPairing);
        return w.take();
    }
    Bytes operator()(const Welcome& m) const {
        TlvWriter w(MsgType::Welcome);
        w.u16(0x01, m.version);
        w.str(0x02, m.receiverName);
        w.raw(0x03, m.sessionId);
        w.u16(0x04, m.displayWidth);
        w.u16(0x05, m.displayHeight);
        w.u8(0x06, m.displayRefresh);
        w.pairs(0x07, m.videoDecoders);
        w.pairs(0x08, m.audioDecoders);
        w.raw(0x09, m.attachKey);
        w.raw(0x0A, m.resumeToken);
        if (m.pairKey)
            w.raw(0x0B, *m.pairKey);
        if (m.keyId)
            w.raw(0x0C, *m.keyId);
        w.boolean(0x0D, m.audioEnabled);
        if (m.rotatedFrames)
            w.boolean(0x0E, true);
        return w.take();
    }
    Bytes operator()(const Reject& m) const {
        TlvWriter w(MsgType::Reject);
        w.u8(0x01, m.reason);
        if (m.retryAfterMs)
            w.u32(0x02, *m.retryAfterMs);
        return w.take();
    }
    Bytes operator()(const PendingApproval& m) const {
        TlvWriter w(MsgType::PendingApproval);
        w.u32(0x01, m.timeoutMs);
        return w.take();
    }
    Bytes operator()(const StreamConfig& m) const {
        TlvWriter w(MsgType::StreamConfig);
        w.u8(0x01, m.streamKind);
        w.u8(0x02, m.codec);
        if (m.width)
            w.u16(0x03, *m.width);
        if (m.height)
            w.u16(0x04, *m.height);
        if (m.fps)
            w.u8(0x05, *m.fps);
        if (m.bitrateBps)
            w.u32(0x06, *m.bitrateBps);
        if (m.sampleRate)
            w.u32(0x07, *m.sampleRate);
        if (m.channels)
            w.u8(0x08, *m.channels);
        if (m.rotationDeg)
            w.u16(0x09, *m.rotationDeg);
        return w.take();
    }
    Bytes operator()(const KeyframeRequest& m) const {
        TlvWriter w(MsgType::KeyframeRequest);
        w.u8(0x01, m.reason);
        return w.take();
    }
    Bytes operator()(const Ping& m) const {
        TlvWriter w(MsgType::Ping);
        w.u32(0x01, m.pingId);
        w.u64(0x02, m.t0Us);
        if (m.offsetUs)
            w.u64(0x03, static_cast<std::uint64_t>(*m.offsetUs));
        return w.take();
    }
    Bytes operator()(const Pong& m) const {
        TlvWriter w(MsgType::Pong);
        w.u32(0x01, m.pingId);
        w.u64(0x02, m.t0Us);
        w.u64(0x03, m.t1Us);
        w.u64(0x04, m.t2Us);
        return w.take();
    }
    Bytes operator()(const ReceiverReport& m) const {
        TlvWriter w(MsgType::ReceiverReport);
        w.u16(0x01, m.intervalMs);
        w.u32(0x02, m.framesReceived);
        w.u32(0x03, m.framesRendered);
        w.u32(0x04, m.framesDropped);
        w.u32(0x05, m.bytesReceived);
        w.u32(0x06, m.c2rAvgUs);
        w.u32(0x07, m.c2rMinUs);
        w.u32(0x08, m.c2rMaxUs);
        w.u32(0x09, m.c2dAvgUs);
        w.u16(0x0A, m.decodeBacklog);
        w.u16(0x0B, m.audioLate);
        return w.take();
    }
    Bytes operator()(const SenderStats& m) const {
        TlvWriter w(MsgType::SenderStats);
        w.u32(0x01, m.bitrateBps);
        w.u16(0x02, m.fpsX100);
        w.u32(0x03, m.framesDropped);
        if (m.encoderName)
            w.str(0x04, *m.encoderName);
        return w.take();
    }
    Bytes operator()(const Pause&) const { return TlvWriter(MsgType::Pause).take(); }
    Bytes operator()(const Unpause&) const { return TlvWriter(MsgType::Unpause).take(); }
    Bytes operator()(const Attach& m) const {
        TlvWriter w(MsgType::Attach);
        w.raw(0x01, m.sessionId);
        w.u8(0x02, m.streamKind);
        w.raw(0x03, m.proof);
        return w.take();
    }
    Bytes operator()(const AttachResult& m) const {
        TlvWriter w(MsgType::AttachResult);
        w.u8(0x01, m.status);
        return w.take();
    }
    Bytes operator()(const Resume& m) const {
        TlvWriter w(MsgType::Resume);
        w.raw(0x01, m.sessionId);
        w.raw(0x02, m.proof);
        return w.take();
    }
    Bytes operator()(const Bye& m) const {
        TlvWriter w(MsgType::Bye);
        w.u8(0x01, m.reason);
        return w.take();
    }
    Bytes operator()(const UnknownMessage&) const { throw std::invalid_argument("UnknownMessage kodlanamaz"); }
};

} // namespace

Bytes encodeControl(const ControlMessage& msg) { return std::visit(Encoder{}, msg); }

ControlMessage decodeControl(ByteView payload) {
    const TlvReader r(payload);
    switch (r.msgType) {
    case MsgType::Challenge:
        return Challenge{r.u16(0x01), r.u16(0x02), r.arr<16>(0x03), r.str(0x04, false)};
    case MsgType::Hello: {
        Hello m;
        m.version = r.u16(0x01);
        m.deviceName = r.str(0x02);
        m.deviceId = r.arr<16>(0x03);
        m.appVersion = r.str(0x04);
        m.authMethod = r.u8(0x05);
        m.clientNonce = r.arr<16>(0x06);
        m.authProof = r.arr<32>(0x07);
        m.keyId = r.arr<16>(0x08, false);
        m.videoCodecs = *r.list(0x09, true);
        m.audioCodecs = r.list(0x0A, false);
        m.maxWidth = r.u16(0x0B);
        m.maxHeight = r.u16(0x0C);
        m.maxFps = r.u8(0x0D);
        m.requestPairing = r.boolean(0x0E, false);
        return m;
    }
    case MsgType::Welcome: {
        Welcome m;
        m.version = r.u16(0x01);
        m.receiverName = r.str(0x02);
        m.sessionId = r.arr<16>(0x03);
        m.displayWidth = r.u16(0x04);
        m.displayHeight = r.u16(0x05);
        m.displayRefresh = r.u8(0x06);
        m.videoDecoders = r.pairs(0x07);
        m.audioDecoders = r.pairs(0x08);
        m.attachKey = r.arr<32>(0x09);
        m.resumeToken = r.arr<32>(0x0A);
        m.pairKey = r.arr<32>(0x0B, false);
        m.keyId = r.arr<16>(0x0C, false);
        m.audioEnabled = *r.boolean(0x0D, true);
        m.rotatedFrames = r.boolean(0x0E, false).value_or(false);
        return m;
    }
    case MsgType::Reject:
        return Reject{r.u8(0x01), r.u32(0x02, false)};
    case MsgType::PendingApproval:
        return PendingApproval{r.u32(0x01)};
    case MsgType::StreamConfig: {
        StreamConfig m;
        m.streamKind = r.u8(0x01);
        m.codec = r.u8(0x02);
        m.width = r.u16(0x03, false);
        m.height = r.u16(0x04, false);
        m.fps = r.u8(0x05, false);
        m.bitrateBps = r.u32(0x06, false);
        m.sampleRate = r.u32(0x07, false);
        m.channels = r.u8(0x08, false);
        m.rotationDeg = r.u16(0x09, false);
        return m;
    }
    case MsgType::KeyframeRequest:
        return KeyframeRequest{r.u8(0x01)};
    case MsgType::Ping: {
        Ping m{r.u32(0x01), r.u64(0x02), std::nullopt};
        if (auto off = r.u64(0x03, false))
            m.offsetUs = static_cast<std::int64_t>(*off);
        return m;
    }
    case MsgType::Pong:
        return Pong{r.u32(0x01), r.u64(0x02), r.u64(0x03), r.u64(0x04)};
    case MsgType::ReceiverReport:
        return ReceiverReport{r.u16(0x01), r.u32(0x02), r.u32(0x03), r.u32(0x04), r.u32(0x05), r.u32(0x06),
                              r.u32(0x07), r.u32(0x08), r.u32(0x09), r.u16(0x0A), r.u16(0x0B)};
    case MsgType::SenderStats:
        return SenderStats{r.u32(0x01), r.u16(0x02), r.u32(0x03), r.str(0x04, false)};
    case MsgType::Pause:
        return Pause{};
    case MsgType::Unpause:
        return Unpause{};
    case MsgType::Attach:
        return Attach{r.arr<16>(0x01), r.u8(0x02), r.arr<32>(0x03)};
    case MsgType::AttachResult:
        return AttachResult{r.u8(0x01)};
    case MsgType::Resume:
        return Resume{r.arr<16>(0x01), r.arr<32>(0x02)};
    case MsgType::Bye:
        return Bye{r.u8(0x01)};
    default:
        if (r.msgFlags & MsgType::FlagCritical)
            throw ProtocolException(ProtocolError::UnknownCriticalMessage, std::to_string(r.msgType));
        return UnknownMessage{r.msgType, r.msgFlags};
    }
}

} // namespace yansit::protocol
