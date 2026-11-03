#include "media/AudioPipeline.h"

#include "common/Clock.h"
#include "protocol/Control.h"

#include <gst/app/gstappsrc.h>
#include <gst/gst.h>

#include <QLoggingCategory>

#include <algorithm>
#include <vector>

Q_LOGGING_CATEGORY(lcAudio, "yansit.audio")

namespace yansit::media {

using namespace protocol;

namespace {

bool factoryExists(const char* name) {
    GstElementFactory* f = gst_element_factory_find(name);
    if (!f)
        return false;
    gst_object_unref(f);
    return true;
}

const char* sinkFactory(AudioPipeline::SinkMode mode) {
    return mode == AudioPipeline::SinkMode::Device ? "autoaudiosink" : "fakesink";
}

bool outputAvailable(AudioPipeline::SinkMode mode) {
    return factoryExists("audioconvert") && factoryExists("audioresample") && factoryExists(sinkFactory(mode));
}

GstPadProbeReturn sinkProbe(GstPad*, GstPadProbeInfo* info, gpointer self) {
    static_cast<AudioPipeline*>(self)->handleSinkProbe(info);
    return GST_PAD_PROBE_OK;
}

/// RFC 7845 §5.1 OpusHead: "OpusHead" | sürüm | kanal | ön atlama (LE16) | örnek hızı (LE32) | kazanç | eşleme.
std::optional<int> opusChannels(const Bytes& head) {
    static constexpr char kMagic[] = "OpusHead";
    if (head.size() < 19 || !std::equal(kMagic, kMagic + 8, head.begin()))
        return std::nullopt;
    const int channels = head[9];
    if (channels < 1 || channels > 2 || head[18] != 0) // yalnız eşleme ailesi 0 (mono/stereo)
        return std::nullopt;
    return channels;
}

constexpr std::uint64_t kLateMarginUs = 5'000;
constexpr std::uint64_t kArrivalMarginUs = 10'000;
constexpr std::size_t kArrivalWindow = 250; // ≈ 5 sn Opus
constexpr std::uint64_t kLatencyApplyThresholdNs = 10'000'000;

} // namespace

bool AudioPipeline::opusAvailable(SinkMode mode) { return factoryExists("opusdec") && outputAvailable(mode); }

bool AudioPipeline::aacAvailable(SinkMode mode) { return factoryExists("avdec_aac") && outputAvailable(mode); }

AudioPipeline::AudioPipeline(SinkMode mode, QObject* parent) : QObject(parent), m_mode(mode) {
    m_busTimer.setInterval(200);
    connect(&m_busTimer, &QTimer::timeout, this, &AudioPipeline::pollBus);
}

AudioPipeline::~AudioPipeline() { teardown(); }

void AudioPipeline::setClockOffset(std::optional<std::int64_t> offsetUs) {
    std::lock_guard lock(m_mutex);
    m_offsetUs = offsetUs;
}

void AudioPipeline::setTargetLatencyUs(std::uint64_t videoUs) {
    std::uint64_t audioNeed = 0;
    if (m_arrivalUs.size() >= 10) {
        std::vector<std::uint32_t> v(m_arrivalUs.begin(), m_arrivalUs.end());
        const std::size_t k = v.size() * 95 / 100;
        std::nth_element(v.begin(), v.begin() + std::ptrdiff_t(k), v.end());
        audioNeed = std::uint64_t(v[k]) + kArrivalMarginUs;
    }
    m_targetUs = std::clamp(std::max(videoUs, audioNeed), kMinTargetLatencyUs, kMaxTargetLatencyUs);
}

void AudioPipeline::reset() {
    teardown();
    m_codec = 0;
    m_config.clear();
    m_targetUs = kMinTargetLatencyUs;
    m_arrivalUs.clear();
    m_totalReceived = 0;
    m_totalLate = 0;
    m_totalPlayed = 0;
    std::lock_guard lock(m_mutex);
    m_offsetUs.reset();
    m_stats = {};
}

void AudioPipeline::stop() { teardown(); }

void AudioPipeline::teardown() {
    m_busTimer.stop();
    if (m_pipeline) {
        gst_element_set_state(m_pipeline, GST_STATE_NULL);
        gst_object_unref(m_pipeline);
        m_pipeline = nullptr;
        m_appsrc = nullptr;
    }
    m_appliedLatencyNs = 0;
}

bool AudioPipeline::build(std::uint8_t codec, const Bytes& config) {
    teardown();
    GstCaps* caps = nullptr;
    const char* decoderName = nullptr;
    if (codec == Codec::Opus) {
        const auto channels = opusChannels(config);
        if (!channels) {
            qCWarning(lcAudio) << "geçersiz OpusHead";
            return false;
        }
        caps = gst_caps_new_simple("audio/x-opus", "rate", G_TYPE_INT, 48000, "channels", G_TYPE_INT, *channels,
                                   "channel-mapping-family", G_TYPE_INT, 0, "stream-count", G_TYPE_INT, 1,
                                   "coupled-count", G_TYPE_INT, *channels == 2 ? 1 : 0, nullptr);
        decoderName = "opusdec";
    } else if (codec == Codec::AacLc) {
        if (config.size() < 2) {
            qCWarning(lcAudio) << "geçersiz AudioSpecificConfig";
            return false;
        }
        GstBuffer* asc = gst_buffer_new_memdup(config.data(), config.size());
        caps = gst_caps_new_simple("audio/mpeg", "mpegversion", G_TYPE_INT, 4, "stream-format", G_TYPE_STRING, "raw",
                                   "codec_data", GST_TYPE_BUFFER, asc, nullptr);
        gst_buffer_unref(asc);
        decoderName = "avdec_aac";
    } else {
        qCWarning(lcAudio) << "desteklenmeyen ses codec'i:" << codec;
        return false;
    }

    GstElement* pipeline = gst_pipeline_new("yansit-audio");
    GstElement* src = gst_element_factory_make("appsrc", "src");
    // Hedef gecikme kadar tampon gerekir: kuyruk olmadan sink gecikmeyi karşılayamaz ("please add queues").
    GstElement* queue = gst_element_factory_make("queue", nullptr);
    GstElement* dec = gst_element_factory_make(decoderName, "decoder");
    GstElement* conv = gst_element_factory_make("audioconvert", nullptr);
    GstElement* res = gst_element_factory_make("audioresample", nullptr);
    GstElement* sink = gst_element_factory_make(sinkFactory(m_mode), "sink");
    if (!src || !queue || !dec || !conv || !res || !sink) {
        qCWarning(lcAudio) << "ses öğeleri oluşturulamadı";
        for (GstElement* e : {src, queue, dec, conv, res, sink})
            if (e)
                gst_object_unref(e);
        gst_object_unref(pipeline);
        gst_caps_unref(caps);
        return false;
    }
    g_object_set(src, "caps", caps, "is-live", TRUE, "format", GST_FORMAT_TIME, "do-timestamp", FALSE, "min-latency",
                 gint64(0), "max-latency", gint64(kMaxTargetLatencyUs * 2 * 1000), nullptr);
    g_object_set(queue, "max-size-buffers", 0u, "max-size-bytes", 0u, "max-size-time", guint64(GST_SECOND), nullptr);
    gst_caps_unref(caps);
    if (m_mode == SinkMode::Fake)
        g_object_set(sink, "sync", TRUE, nullptr);

    gst_bin_add_many(GST_BIN(pipeline), src, queue, dec, conv, res, sink, nullptr);
    if (!gst_element_link_many(src, queue, dec, conv, res, sink, nullptr)) {
        qCWarning(lcAudio) << "ses öğeleri bağlanamadı";
        gst_object_unref(pipeline);
        return false;
    }

    // Zaman tabanı: sistem monotonik saati ve sıfır temel zaman → çalışma zamanı = CLOCK_MONOTONIC (ns).
    // Böylece paket zaman damgası doğrudan "çalınacağı an" olur; ses aygıtının kendi saati bu saate kölelenir.
    GstClock* clock = gst_system_clock_obtain();
    g_object_set(clock, "clock-type", GST_CLOCK_TYPE_MONOTONIC, nullptr);
    gst_pipeline_use_clock(GST_PIPELINE(pipeline), clock);
    gst_object_unref(clock);
    gst_element_set_start_time(pipeline, GST_CLOCK_TIME_NONE);
    gst_element_set_base_time(pipeline, 0);

    GstPad* pad = gst_element_get_static_pad(sink, "sink");
    if (pad) {
        gst_pad_add_probe(pad, GST_PAD_PROBE_TYPE_BUFFER, sinkProbe, this, nullptr);
        gst_object_unref(pad);
    }

    m_pipeline = pipeline;
    m_appsrc = src;
    m_appliedLatencyNs = m_targetUs.load() * 1000;
    gst_pipeline_set_latency(GST_PIPELINE(pipeline), m_appliedLatencyNs.load());
    if (gst_element_set_state(pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
        qCWarning(lcAudio) << "ses boru hattı başlatılamadı (ses aygıtı yok mu?)";
        teardown();
        return false;
    }
    m_busTimer.start();
    qCInfo(lcAudio) << "ses boru hattı:" << decoderName << "→" << sinkFactory(m_mode);
    return true;
}

void AudioPipeline::pollBus() {
    if (!m_pipeline)
        return;
    GstBus* bus = gst_element_get_bus(m_pipeline);
    bool failed = false;
    while (GstMessage* msg = gst_bus_pop_filtered(bus, GstMessageType(GST_MESSAGE_ERROR | GST_MESSAGE_WARNING))) {
        GError* err = nullptr;
        gchar* debug = nullptr;
        if (GST_MESSAGE_TYPE(msg) == GST_MESSAGE_ERROR) {
            gst_message_parse_error(msg, &err, &debug);
            qCWarning(lcAudio) << "GStreamer hatası:" << GST_OBJECT_NAME(GST_MESSAGE_SRC(msg)) << err->message;
            failed = true;
        } else {
            gst_message_parse_warning(msg, &err, &debug);
            qCInfo(lcAudio) << "GStreamer uyarısı:" << err->message;
        }
        g_clear_error(&err);
        g_free(debug);
        gst_message_unref(msg);
    }
    gst_object_unref(bus);
    if (failed) {
        // Video etkilenmez; sonraki CONFIG ile yeniden denenir.
        teardown();
    }
}

void AudioPipeline::pushFrame(const FrameHeader& header, Bytes&& payload, std::uint64_t recvUs) {
    if (header.isConfig()) {
        if (payload.empty())
            return;
        m_codec = payload[0];
        m_config.assign(payload.begin() + 1, payload.end());
        build(m_codec, m_config);
        return;
    }
    ++m_totalReceived;
    std::optional<std::int64_t> offset;
    {
        std::lock_guard lock(m_mutex);
        ++m_stats.received;
        offset = m_offsetUs;
    }
    // Saat ofseti bilinmeden çalma anı hesaplanamaz (ilk PING gelene kadar, ≈ ilk saniye).
    if (!m_pipeline || !offset) {
        return;
    }
    const std::int64_t captureLocalUs = std::int64_t(header.ptsUs) + *offset;
    m_arrivalUs.push_back(std::uint32_t(std::clamp<std::int64_t>(std::int64_t(recvUs) - captureLocalUs, 0, 1'000'000)));
    if (m_arrivalUs.size() > kArrivalWindow)
        m_arrivalUs.pop_front();
    const std::uint64_t target = m_targetUs.load();
    if (captureLocalUs + std::int64_t(target) < std::int64_t(recvUs + kLateMarginUs)) {
        ++m_totalLate;
        std::lock_guard lock(m_mutex);
        ++m_stats.late;
        return;
    }
    // Hedef gecikme belirgin değiştiyse boru hattı gecikmesini güncelle (videoyla senkron kalmak için).
    const std::uint64_t wantNs = target * 1000;
    const std::uint64_t applied = m_appliedLatencyNs.load();
    const std::uint64_t diff = wantNs > applied ? wantNs - applied : applied - wantNs;
    if (diff >= kLatencyApplyThresholdNs) {
        m_appliedLatencyNs = wantNs;
        gst_pipeline_set_latency(GST_PIPELINE(m_pipeline), wantNs);
        qCDebug(lcAudio) << "ses gecikmesi:" << target / 1000 << "ms";
    }

    auto* data = new Bytes(std::move(payload));
    GstBuffer* buf = gst_buffer_new_wrapped_full(GST_MEMORY_FLAG_READONLY, data->data(), data->size(), 0,
                                                 data->size(), data, [](gpointer p) { delete static_cast<Bytes*>(p); });
    GST_BUFFER_PTS(buf) = GstClockTime(captureLocalUs) * 1000;
    gst_app_src_push_buffer(GST_APP_SRC(m_appsrc), buf);
}

void AudioPipeline::handleSinkProbe(GstPadProbeInfo* info) {
    GstBuffer* buf = GST_PAD_PROBE_INFO_BUFFER(info);
    if (!buf || !GST_BUFFER_PTS_IS_VALID(buf))
        return;
    // Çalma anı = PTS + boru hattı gecikmesi; yakalama anına göre ses gecikmesi.
    const std::uint64_t captureUs = GST_BUFFER_PTS(buf) / 1000;
    const std::uint64_t playUs = captureUs + m_appliedLatencyNs / 1000;
    ++m_totalPlayed;
    std::lock_guard lock(m_mutex);
    ++m_stats.played;
    if (playUs > captureUs) {
        m_stats.latencySumUs += playUs - captureUs;
        ++m_stats.latencyCount;
    }
}

AudioPipeline::Stats AudioPipeline::takeStats() {
    std::lock_guard lock(m_mutex);
    Stats s = m_stats;
    m_stats = {};
    return s;
}

} // namespace yansit::media
