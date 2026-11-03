// protocol/vectors/vectors.json ortak test vektörleri: Kotlin uygulaması aynı dosyayı test eder.
#include "protocol/Control.h"
#include "protocol/Crypto.h"
#include "protocol/Errors.h"
#include "protocol/Frame.h"
#include "protocol/Qr.h"

#include <fstream>
#include <gtest/gtest.h>
#include <nlohmann/json.hpp>

using namespace yansit::protocol;
using json = nlohmann::json;

namespace {

const json& vectors() {
    static const json v = [] {
        std::ifstream f(std::string(YANSIT_VECTORS_DIR) + "/vectors.json");
        if (!f)
            throw std::runtime_error("vectors.json açılamadı: " YANSIT_VECTORS_DIR);
        return json::parse(f);
    }();
    return v;
}

template <typename T>
void opt(json& j, const char* key, const std::optional<T>& v) {
    if (v)
        j[key] = *v;
}
std::string hx(ByteView b) { return toHex(b); }
template <std::size_t N>
void optHex(json& j, const char* key, const std::optional<std::array<std::uint8_t, N>>& v) {
    if (v)
        j[key] = hx(*v);
}
json pairs(const std::vector<CodecSupport>& v) {
    json a = json::array();
    for (const auto& p : v)
        a.push_back({p.codec, p.flags});
    return a;
}
json list(const std::vector<std::uint8_t>& v) {
    json a = json::array();
    for (auto x : v)
        a.push_back(x);
    return a;
}

struct ToFields {
    std::pair<std::string, json> operator()(const Challenge& m) const {
        json j{{"min_version", m.minVersion}, {"max_version", m.maxVersion}, {"server_nonce", hx(m.serverNonce)}};
        opt(j, "receiver_name", m.receiverName);
        return {"CHALLENGE", j};
    }
    std::pair<std::string, json> operator()(const Hello& m) const {
        json j{{"version", m.version},         {"device_name", m.deviceName},   {"device_id", hx(m.deviceId)},
               {"app_version", m.appVersion},  {"auth_method", m.authMethod},   {"client_nonce", hx(m.clientNonce)},
               {"auth_proof", hx(m.authProof)}, {"video_codecs", list(m.videoCodecs)}, {"max_width", m.maxWidth},
               {"max_height", m.maxHeight},    {"max_fps", m.maxFps}};
        optHex(j, "key_id", m.keyId);
        if (m.audioCodecs)
            j["audio_codecs"] = list(*m.audioCodecs);
        opt(j, "request_pairing", m.requestPairing);
        return {"HELLO", j};
    }
    std::pair<std::string, json> operator()(const Welcome& m) const {
        json j{{"version", m.version},
               {"receiver_name", m.receiverName},
               {"session_id", hx(m.sessionId)},
               {"display_width", m.displayWidth},
               {"display_height", m.displayHeight},
               {"display_refresh", m.displayRefresh},
               {"video_decoders", pairs(m.videoDecoders)},
               {"audio_decoders", pairs(m.audioDecoders)},
               {"attach_key", hx(m.attachKey)},
               {"resume_token", hx(m.resumeToken)},
               {"audio_enabled", m.audioEnabled}};
        optHex(j, "pair_key", m.pairKey);
        optHex(j, "key_id", m.keyId);
        return {"WELCOME", j};
    }
    std::pair<std::string, json> operator()(const Reject& m) const {
        json j{{"reason", m.reason}};
        opt(j, "retry_after_ms", m.retryAfterMs);
        return {"REJECT", j};
    }
    std::pair<std::string, json> operator()(const PendingApproval& m) const {
        return {"PENDING_APPROVAL", {{"timeout_ms", m.timeoutMs}}};
    }
    std::pair<std::string, json> operator()(const StreamConfig& m) const {
        json j{{"stream_kind", m.streamKind}, {"codec", m.codec}};
        opt(j, "width", m.width);
        opt(j, "height", m.height);
        opt(j, "fps", m.fps);
        opt(j, "bitrate_bps", m.bitrateBps);
        opt(j, "sample_rate", m.sampleRate);
        opt(j, "channels", m.channels);
        opt(j, "rotation_deg", m.rotationDeg);
        return {"STREAM_CONFIG", j};
    }
    std::pair<std::string, json> operator()(const KeyframeRequest& m) const {
        return {"KEYFRAME_REQUEST", {{"reason", m.reason}}};
    }
    std::pair<std::string, json> operator()(const Ping& m) const {
        json j{{"ping_id", m.pingId}, {"t0_us", m.t0Us}};
        opt(j, "offset_us", m.offsetUs);
        return {"PING", j};
    }
    std::pair<std::string, json> operator()(const Pong& m) const {
        return {"PONG", {{"ping_id", m.pingId}, {"t0_us", m.t0Us}, {"t1_us", m.t1Us}, {"t2_us", m.t2Us}}};
    }
    std::pair<std::string, json> operator()(const ReceiverReport& m) const {
        return {"RECEIVER_REPORT",
                {{"interval_ms", m.intervalMs},
                 {"frames_received", m.framesReceived},
                 {"frames_rendered", m.framesRendered},
                 {"frames_dropped", m.framesDropped},
                 {"bytes_received", m.bytesReceived},
                 {"c2r_avg_us", m.c2rAvgUs},
                 {"c2r_min_us", m.c2rMinUs},
                 {"c2r_max_us", m.c2rMaxUs},
                 {"c2d_avg_us", m.c2dAvgUs},
                 {"decode_backlog", m.decodeBacklog},
                 {"audio_late", m.audioLate}}};
    }
    std::pair<std::string, json> operator()(const SenderStats& m) const {
        json j{{"bitrate_bps", m.bitrateBps}, {"fps_x100", m.fpsX100}, {"frames_dropped", m.framesDropped}};
        opt(j, "encoder_name", m.encoderName);
        return {"SENDER_STATS", j};
    }
    std::pair<std::string, json> operator()(const Pause&) const { return {"PAUSE", json::object()}; }
    std::pair<std::string, json> operator()(const Unpause&) const { return {"UNPAUSE", json::object()}; }
    std::pair<std::string, json> operator()(const Attach& m) const {
        return {"ATTACH", {{"session_id", hx(m.sessionId)}, {"stream_kind", m.streamKind}, {"proof", hx(m.proof)}}};
    }
    std::pair<std::string, json> operator()(const AttachResult& m) const {
        return {"ATTACH_RESULT", {{"status", m.status}}};
    }
    std::pair<std::string, json> operator()(const Resume& m) const {
        return {"RESUME", {{"session_id", hx(m.sessionId)}, {"proof", hx(m.proof)}}};
    }
    std::pair<std::string, json> operator()(const Bye& m) const { return {"BYE", {{"reason", m.reason}}}; }
    std::pair<std::string, json> operator()(const UnknownMessage& m) const {
        return {"UNKNOWN", {{"msg_type", m.msgType}}};
    }
};

template <typename F>
std::string errorOf(F&& f) {
    try {
        f();
    } catch (const ProtocolException& e) {
        return std::string(errorName(e.error()));
    }
    return "<hata yok>";
}

} // namespace

TEST(Vectors, ErrorCodeNamesMatch) {
    const auto& names = vectors()["error_codes"];
    ASSERT_EQ(names.size(), 13u);
    for (int i = 0; i < 13; ++i)
        EXPECT_EQ(names[i].get<std::string>(), errorName(static_cast<ProtocolError>(i)));
}

TEST(Vectors, Frames) {
    ASSERT_FALSE(vectors()["frames"].empty());
    for (const auto& v : vectors()["frames"]) {
        SCOPED_TRACE(v["name"].get<std::string>());
        const Bytes bytes = fromHex(v["hex"].get<std::string>());
        const bool headerOnly = v.value("header_only", false);
        const auto decode = [&] {
            const FrameHeader h = decodeHeader(bytes);
            if (!headerOnly && bytes.size() - kHeaderSize < h.length)
                throw ProtocolException(ProtocolError::Truncated, "yük");
            return h;
        };
        if (v.contains("expect_error")) {
            EXPECT_EQ(errorOf(decode), v["expect_error"].get<std::string>());
            continue;
        }
        const FrameHeader h = decode();
        const auto& ex = v["expect"];
        EXPECT_EQ(int(h.type), ex["type"].get<int>());
        EXPECT_EQ(h.flags, ex["flags"].get<int>());
        EXPECT_EQ(h.ptsUs, ex["pts_us"].get<std::uint64_t>());
        EXPECT_EQ(h.seq, ex["seq"].get<std::uint32_t>());
        EXPECT_EQ(h.length, ex["length"].get<std::uint32_t>());
        if (!headerOnly) {
            const ByteView payload = ByteView(bytes).subspan(kHeaderSize);
            EXPECT_EQ(toHex(payload), ex["payload"].get<std::string>());
            if (bytes[6] == 0 && bytes[7] == 0) {
                EXPECT_EQ(encodeFrame(h.type, h.flags, h.ptsUs, h.seq, payload), bytes);
            }
        }
    }
}

TEST(Vectors, Control) {
    ASSERT_FALSE(vectors()["control"].empty());
    for (const auto& v : vectors()["control"]) {
        SCOPED_TRACE(v["name"].get<std::string>());
        const Bytes bytes = fromHex(v["hex"].get<std::string>());
        if (v.contains("expect_error")) {
            EXPECT_EQ(errorOf([&] { decodeControl(bytes); }), v["expect_error"].get<std::string>());
            continue;
        }
        const ControlMessage msg = decodeControl(bytes);
        const auto [name, fields] = std::visit(ToFields{}, msg);
        EXPECT_EQ(name, v["expect"]["message"].get<std::string>());
        EXPECT_EQ(fields, v["expect"]["fields"]) << fields.dump();
        if (v["roundtrip"].get<bool>()) {
            EXPECT_EQ(toHex(encodeControl(msg)), v["hex"].get<std::string>());
        }
    }
}

TEST(Vectors, Crypto) {
    ASSERT_FALSE(vectors()["crypto"].empty());
    for (const auto& v : vectors()["crypto"]) {
        SCOPED_TRACE(v["name"].get<std::string>());
        const auto& in = v["inputs"];
        const auto b = [&](const char* k) { return fromHex(in[k].get<std::string>()); };
        const std::string kind = v["kind"];
        Key32 actual{};
        if (kind == "auth") {
            actual = authProof(b("secret"), in["auth_method"].get<std::uint8_t>(), toArray<16>(b("server_nonce")),
                               toArray<16>(b("client_nonce")), toArray<32>(b("receiver_cert_fp")),
                               toArray<16>(b("device_id")));
        } else if (kind == "attach") {
            actual = attachProof(toArray<32>(b("attach_key")), toArray<16>(b("session_id")),
                                 in["stream_kind"].get<std::uint8_t>(), toArray<16>(b("server_nonce")));
        } else if (kind == "resume") {
            actual = resumeProof(toArray<32>(b("resume_token")), toArray<16>(b("session_id")),
                                 toArray<16>(b("server_nonce")));
        } else if (kind == "sha256") {
            actual = sha256(b("data"));
        } else {
            FAIL() << "bilinmeyen kind " << kind;
        }
        EXPECT_EQ(toHex(actual), v["expect"].get<std::string>());
    }
}

TEST(Vectors, QrBuild) {
    int built = 0;
    ASSERT_FALSE(vectors()["qr"].empty());
    for (const auto& v : vectors()["qr"]) {
        if (!v.contains("fields"))
            continue; // ayrıştırma hataları yalnız göndericiyi (Kotlin) ilgilendirir
        SCOPED_TRACE(v["name"].get<std::string>());
        const auto& f = v["fields"];
        QrPayload p;
        p.hosts = f["hosts"].get<std::vector<std::string>>();
        p.port = f["port"].get<std::uint16_t>();
        p.token = toArray<16>(fromHex(f["token"].get<std::string>()));
        p.certFingerprint = toArray<32>(fromHex(f["cert_fp"].get<std::string>()));
        p.name = f["name"].get<std::string>();
        EXPECT_EQ(buildQrUri(p), v["uri"].get<std::string>());
        ++built;
    }
    EXPECT_GE(built, 3);
}

TEST(Qr, RejectsInvalidInput) {
    QrPayload p;
    p.port = 47800;
    EXPECT_THROW(buildQrUri(p), std::invalid_argument);
    p.hosts = {"10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5"};
    EXPECT_THROW(buildQrUri(p), std::invalid_argument);
    p.hosts = {"10.0.0.256"};
    EXPECT_THROW(buildQrUri(p), std::invalid_argument);
    p.hosts = {"01.0.0.1"};
    EXPECT_THROW(buildQrUri(p), std::invalid_argument);
    p.hosts = {"10.0.0.1"};
    p.port = 0;
    EXPECT_THROW(buildQrUri(p), std::invalid_argument);
}

TEST(Base64Url, AllPaddingLengths) {
    const Bytes b = fromHex("fbff");
    EXPECT_EQ(base64UrlNoPad(ByteView(b).first(0)), "");
    EXPECT_EQ(base64UrlNoPad(ByteView(b).first(1)), "-w");
    EXPECT_EQ(base64UrlNoPad(b), "-_8");
    EXPECT_EQ(base64UrlNoPad(fromHex("000000")), "AAAA");
}
