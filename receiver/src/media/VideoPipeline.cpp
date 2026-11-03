#include "media/VideoPipeline.h"

#include "common/Clock.h"
#include "media/LatencyPattern.h"
#include "protocol/Control.h"

#include <gst/app/gstappsrc.h>
#include <gst/gst.h>
#include <gst/video/video.h>

#include <QLoggingCategory>

#include <algorithm>
#include <array>
#include <cstdlib>

Q_LOGGING_CATEGORY(lcVideo, "yansit.video")

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

bool isHardwareFactory(const std::string& name) { return name.rfind("va", 0) == 0; }

bool hasProperty(GstElement* e, const char* prop) {
    return g_object_class_find_property(G_OBJECT_GET_CLASS(e), prop) != nullptr;
}

GstPadProbeReturn sinkProbe(GstPad*, GstPadProbeInfo* info, gpointer self) {
    static_cast<VideoPipeline*>(self)->handleSinkProbe(info);
    return GST_PAD_PROBE_OK;
}

GstPadProbeReturn decodedProbe(GstPad*, GstPadProbeInfo* info, gpointer self) {
    static_cast<VideoPipeline*>(self)->handleDecodedProbe(info);
    return GST_PAD_PROBE_OK;
}

constexpr std::size_t kMaxE2eSamples = 3600; // ≈ 1 dk @ 60 fps

constexpr std::uint64_t kKeyframeRequestIntervalUs = 200'000;
constexpr std::uint64_t kMinRebuildIntervalUs = 1'000'000;

} // namespace

std::vector<DecoderChoice> availableDecoders(std::uint8_t codec) {
    std::vector<DecoderChoice> out;
    const bool hevc = codec == Codec::H265;
    if (const char* forced = std::getenv(hevc ? "YANSIT_DECODER_H265" : "YANSIT_DECODER"); forced && *forced) {
        if (factoryExists(forced))
            out.push_back({forced, isHardwareFactory(forced)});
        else
            qCWarning(lcVideo) << "YANSIT_DECODER bulunamadı:" << forced;
        return out;
    }
    // vah264dec: GStreamer 1.22 VA eklentisi (yalnız çalışan bir VA sürücüsü varsa kayıtlı olur).
    // vaapih264dec: eski gstreamer-vaapi. avdec_h264: FFmpeg yazılım çözücü (her zaman yedek).
    static constexpr std::array<const char*, 3> kH264{"vah264dec", "vaapih264dec", "avdec_h264"};
    static constexpr std::array<const char*, 3> kH265{"vah265dec", "vaapih265dec", "avdec_h265"};
    const auto& names = hevc ? kH265 : kH264;
    for (const char* name : names)
        if (factoryExists(name))
            out.push_back({name, isHardwareFactory(name)});
    return out;
}

VideoPipeline::VideoPipeline(SinkMode mode, QObject* parent) : QObject(parent), m_mode(mode) {
    m_busTimer.setInterval(100);
    connect(&m_busTimer, &QTimer::timeout, this, &VideoPipeline::pollBus);
}

VideoPipeline::~VideoPipeline() {
    teardown();
    if (m_decodedInfo)
        gst_video_info_free(m_decodedInfo);
}

void VideoPipeline::setClockOffset(std::optional<std::int64_t> offsetUs) {
    std::lock_guard lock(m_mutex);
    m_offsetUs = offsetUs;
}

void VideoPipeline::reset() {
    teardown();
    m_codec = 0;
    m_decoders.clear();
    m_config.clear();
    m_configPending = false;
    m_waitKeyframe = true;
    m_lastSeq.reset();
    m_lastBufferPtsNs = 0;
    m_lastKeyframeRequestUs = 0;
    m_backlogSinceUs = 0;
    m_firstRendered = false;
    m_totalRendered = 0;
    m_totalDropped = 0;
    m_totalReceived = 0;
    {
        std::lock_guard lock(m_mutex);
        m_stats = {};
        m_inFlight.clear();
        m_basePtsUs.reset();
        m_offsetUs.reset();
        m_width = m_height = 0;
        m_patternByPts.clear();
        m_decodedInfoValid = false;
    }
    std::lock_guard e2e(m_e2eMutex);
    m_e2eSamples.clear();
    m_e2eSessionSumUs = 0;
    m_e2eSessionCount = 0;
}

void VideoPipeline::stop() { teardown(); }

void VideoPipeline::teardown() {
    m_busTimer.stop();
    if (m_pipeline) {
        gst_element_set_state(m_pipeline, GST_STATE_NULL);
        gst_object_unref(m_pipeline);
        m_pipeline = nullptr;
        m_appsrc = nullptr;
    }
    std::lock_guard lock(m_mutex);
    m_inFlight.clear();
}

bool VideoPipeline::build() {
    teardown();
    m_lastRebuildUs = monotonicUs();
    if (m_decoders.empty()) {
        m_decoders = availableDecoders(m_codec);
        m_decoderIndex = 0;
    }
    if (m_decoderIndex >= m_decoders.size()) {
        qCCritical(lcVideo) << "kullanılabilir çözücü yok, codec" << m_codec << "(gstreamer1.0-libav kurulu mu?)";
        return false;
    }
    m_decoder = m_decoders[m_decoderIndex];
    if (m_mode == SinkMode::Gl && !m_videoItem) {
        qCCritical(lcVideo) << "GL modu için video öğesi ayarlanmamış";
        return false;
    }

    GstElement* pipeline = gst_pipeline_new("yansit-video");
    std::vector<GstElement*> chain;
    const auto make = [&](const char* factory, const char* name = nullptr) {
        GstElement* e = gst_element_factory_make(factory, name);
        if (!e)
            qCCritical(lcVideo) << "GStreamer öğesi oluşturulamadı:" << factory;
        chain.push_back(e);
        return e;
    };

    GstElement* src = make("appsrc", "src");
    const bool hevc = m_codec == Codec::H265;
    make(hevc ? "h265parse" : "h264parse");
    GstElement* dec = make(m_decoder.factory.c_str(), "decoder");
    GstElement* sink = nullptr;
    if (m_mode == SinkMode::Gl) {
        if (m_decoder.hardware && !std::getenv("YANSIT_VA_DMABUF")) {
            // GStreamer 1.22'de VA → GL DMABuf içe aktarımı sürücüye göre kararsız (RISKS R6):
            // varsayılan olarak sistem belleğine indirilip glupload ile yüklenir.
            GstElement* cf = make("capsfilter");
            if (cf) {
                GstCaps* raw = gst_caps_new_empty_simple("video/x-raw");
                g_object_set(cf, "caps", raw, nullptr);
                gst_caps_unref(raw);
            }
        }
        make("glupload");
        make("glcolorconvert");
        sink = make("qml6glsink", "sink");
    } else {
        sink = make("fakesink", "sink");
    }

    if (std::find(chain.begin(), chain.end(), nullptr) != chain.end()) {
        for (GstElement* e : chain)
            if (e)
                gst_object_unref(e);
        gst_object_unref(pipeline);
        // Eksik öğe donanım çözücüye aitse yazılıma düş.
        if (m_decoder.hardware && m_decoderIndex + 1 < m_decoders.size()) {
            ++m_decoderIndex;
            emit decoderChanged();
            return build();
        }
        return false;
    }

    GstCaps* caps = gst_caps_new_simple(hevc ? "video/x-h265" : "video/x-h264", "stream-format", G_TYPE_STRING, "byte-stream", "alignment",
                                        G_TYPE_STRING, "au", nullptr);
    g_object_set(src, "caps", caps, "is-live", TRUE, "format", GST_FORMAT_TIME, "do-timestamp", FALSE, "max-bytes",
                 guint64(0), "block", FALSE, nullptr);
    gst_caps_unref(caps);

    if (hasProperty(dec, "max-threads")) // avdec_h264: kare iş parçacığı başına +1 kare gecikme
        g_object_set(dec, "max-threads", 2, nullptr);
    if (hasProperty(dec, "output-corrupt"))
        g_object_set(dec, "output-corrupt", FALSE, nullptr);

    g_object_set(sink, "sync", FALSE, nullptr);
    if (m_mode == SinkMode::Gl)
        g_object_set(sink, "widget", gpointer(m_videoItem.data()), nullptr);

    for (GstElement* e : chain)
        gst_bin_add(GST_BIN(pipeline), e);
    bool linked = true;
    for (std::size_t i = 0; i + 1 < chain.size() && linked; ++i)
        linked = gst_element_link(chain[i], chain[i + 1]);
    if (!linked) {
        qCCritical(lcVideo) << "GStreamer öğeleri bağlanamadı";
        gst_object_unref(pipeline);
        return false;
    }

    // Gecikme deseni çözücü çıkışında (sistem belleği) okunur; GL dokusu okunamaz.
    if (GstPad* decPad = gst_element_get_static_pad(dec, "src")) {
        gst_pad_add_probe(decPad, GstPadProbeType(GST_PAD_PROBE_TYPE_BUFFER | GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM),
                          decodedProbe, this, nullptr);
        gst_object_unref(decPad);
    }

    GstPad* pad = gst_element_get_static_pad(sink, "sink");
    gst_pad_add_probe(pad, GstPadProbeType(GST_PAD_PROBE_TYPE_BUFFER | GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM),
                      sinkProbe, this, nullptr);
    gst_object_unref(pad);

    m_pipeline = pipeline;
    m_appsrc = src;
    if (gst_element_set_state(pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
        qCWarning(lcVideo) << "boru hattı başlatılamadı:" << m_decoder.factory.c_str();
        pollBus();
        if (m_pipeline) // pollBus hatayı işlemediyse
            onDecoderFailure(QStringLiteral("PLAYING durumuna geçilemedi"));
        return m_pipeline != nullptr;
    }
    m_busTimer.start();
    qCInfo(lcVideo) << "video boru hattı:" << m_decoder.factory.c_str()
                    << (m_decoder.hardware ? "(donanım)" : "(yazılım)");
    return true;
}

void VideoPipeline::pollBus() {
    if (!m_pipeline)
        return;
    GstBus* bus = gst_element_get_bus(m_pipeline);
    QString failure;
    while (GstMessage* msg = gst_bus_pop_filtered(bus, GstMessageType(GST_MESSAGE_ERROR | GST_MESSAGE_WARNING))) {
        GError* err = nullptr;
        gchar* debug = nullptr;
        const QString source = QString::fromUtf8(GST_OBJECT_NAME(GST_MESSAGE_SRC(msg)));
        if (GST_MESSAGE_TYPE(msg) == GST_MESSAGE_ERROR) {
            gst_message_parse_error(msg, &err, &debug);
            failure = source + QStringLiteral(": ") + QString::fromUtf8(err->message);
            qCWarning(lcVideo) << "GStreamer hatası:" << failure << (debug ? debug : "");
        } else {
            gst_message_parse_warning(msg, &err, &debug);
            qCInfo(lcVideo) << "GStreamer uyarısı:" << source << err->message;
        }
        g_clear_error(&err);
        g_free(debug);
        gst_message_unref(msg);
        if (!failure.isEmpty())
            break;
    }
    gst_object_unref(bus);
    if (!failure.isEmpty())
        onDecoderFailure(failure);
}

void VideoPipeline::onDecoderFailure(const QString& message) {
    teardown();
    if (m_decoder.hardware && m_decoderIndex + 1 < m_decoders.size()) {
        ++m_decoderIndex;
        qCWarning(lcVideo) << "donanım çözücü başarısız, yazılıma geçiliyor:" << message;
        emit decoderChanged();
    }
    m_configPending = true;
    m_waitKeyframe = true;

    const auto rebuild = [this] {
        if (m_pipeline || m_config.empty())
            return;
        if (build()) {
            m_lastKeyframeRequestUs = 0;
            requestKeyframe(KeyframeReason::DecodeError);
        }
    };
    // Sürekli hata veren bir ortamda döngüye girmemek için yeniden kurulumu sınırla.
    const std::uint64_t now = monotonicUs();
    if (now - m_lastRebuildUs < kMinRebuildIntervalUs)
        QTimer::singleShot(1000, this, rebuild);
    else
        rebuild();
}

void VideoPipeline::requestKeyframe(std::uint8_t reason) {
    const std::uint64_t now = monotonicUs();
    if (m_lastKeyframeRequestUs != 0 && now - m_lastKeyframeRequestUs < kKeyframeRequestIntervalUs)
        return;
    m_lastKeyframeRequestUs = now;
    emit keyframeNeeded(reason);
}

void VideoPipeline::dropFrame() {
    ++m_totalDropped;
    std::lock_guard lock(m_mutex);
    ++m_stats.framesDropped;
}

void VideoPipeline::pushFrame(const FrameHeader& header, Bytes&& payload, std::uint64_t recvUs) {
    if (header.isConfig()) {
        if (payload.empty())
            return;
        if (payload[0] != Codec::H264 && payload[0] != Codec::H265) {
            qCWarning(lcVideo) << "desteklenmeyen video codec'i:" << payload[0];
            return;
        }
        if (payload[0] != m_codec) {
            // Codec değişti (ör. H.264 → H.265): boru hattı yeni çözücüyle kurulur.
            teardown();
            m_codec = payload[0];
            m_decoders.clear();
        }
        m_config.assign(payload.begin() + 1, payload.end());
        m_configPending = true;
        m_waitKeyframe = true;
        if (!m_pipeline)
            build();
        return;
    }

    ++m_totalReceived;
    {
        std::lock_guard lock(m_mutex);
        ++m_stats.framesReceived;
        m_stats.bytesReceived += payload.size();
        if (!m_basePtsUs)
            m_basePtsUs = header.ptsUs;
        if (m_offsetUs) {
            const std::int64_t c2r = std::int64_t(recvUs) - (std::int64_t(header.ptsUs) + *m_offsetUs);
            const std::uint32_t v = std::uint32_t(std::clamp<std::int64_t>(c2r, 0, 0xFFFFFFFF));
            m_stats.c2rSumUs += v;
            m_stats.c2rMinUs = m_stats.c2rCount == 0 ? v : std::min(m_stats.c2rMinUs, v);
            m_stats.c2rMaxUs = std::max(m_stats.c2rMaxUs, v);
            ++m_stats.c2rCount;
        }
    }

    const bool gap = m_lastSeq && header.seq != *m_lastSeq + 1;
    m_lastSeq = header.seq;
    const bool key = header.isKeyframe();
    if (!key && (gap || header.isDiscontinuity()) && !m_waitKeyframe) {
        m_waitKeyframe = true;
        requestKeyframe(KeyframeReason::BacklogDropped);
    }

    if (!m_pipeline || m_config.empty()) {
        qCDebug(lcVideo) << "kare atıldı: boru hattı/CONFIG yok, seq" << header.seq;
        dropFrame();
        return;
    }
    if (m_waitKeyframe && !key) {
        qCDebug(lcVideo) << "kare atıldı: anahtar kare bekleniyor, seq" << header.seq;
        dropFrame();
        requestKeyframe(KeyframeReason::DecoderReset);
        return;
    }
    std::size_t inFlight = 0;
    {
        std::lock_guard lock(m_mutex);
        inFlight = m_inFlight.size();
    }
    // Kısa birikme (GL bağlamı ısınması, anlık takılma) kendiliğinden erir; ancak kMaxInFlight üstü birikme
    // kBacklogGraceUs boyunca sürerse veya kHardMaxInFlight'ı aşarsa çözücü yetişemiyor demektir.
    if (inFlight < kMaxInFlight)
        m_backlogSinceUs = 0;
    else if (m_backlogSinceUs == 0)
        m_backlogSinceUs = recvUs;
    const bool sustained = m_backlogSinceUs != 0 && m_firstRendered.load() &&
                           recvUs - m_backlogSinceUs >= kBacklogGraceUs;
    const std::size_t hardLimit = m_firstRendered.load() ? kHardMaxInFlight : kStartupMaxInFlight;
    if (!key && (sustained || inFlight >= hardLimit)) {
        // Gecikme biriktirmek yerine anahtar kareye kadar atla.
        m_backlogSinceUs = 0;
        qCDebug(lcVideo) << "kare atıldı: çözücü önünde" << inFlight << "kare, seq" << header.seq;
        dropFrame();
        m_waitKeyframe = true;
        requestKeyframe(KeyframeReason::BacklogDropped);
        return;
    }
    m_waitKeyframe = false;

    auto* data = new Bytes;
    if (key && m_configPending) {
        data->reserve(m_config.size() + payload.size());
        data->insert(data->end(), m_config.begin(), m_config.end());
        data->insert(data->end(), payload.begin(), payload.end());
        m_configPending = false;
    } else {
        *data = std::move(payload);
    }

    std::uint64_t ptsNs = 0;
    {
        std::lock_guard lock(m_mutex);
        ptsNs = header.ptsUs > *m_basePtsUs ? (header.ptsUs - *m_basePtsUs) * 1000 : 0;
        if (m_lastBufferPtsNs != 0 && ptsNs <= m_lastBufferPtsNs)
            ptsNs = m_lastBufferPtsNs + 1;
        m_lastBufferPtsNs = ptsNs;
        m_inFlight.push_back(ptsNs);
    }

    GstBuffer* buf = gst_buffer_new_wrapped_full(GST_MEMORY_FLAG_READONLY, data->data(), data->size(), 0,
                                                 data->size(), data, [](gpointer p) { delete static_cast<Bytes*>(p); });
    GST_BUFFER_PTS(buf) = ptsNs;
    GST_BUFFER_DTS(buf) = ptsNs;
    if (!key)
        GST_BUFFER_FLAG_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT);
    const GstFlowReturn ret = gst_app_src_push_buffer(GST_APP_SRC(m_appsrc), buf);
    if (ret != GST_FLOW_OK)
        qCDebug(lcVideo) << "appsrc push:" << gst_flow_get_name(ret);
}

void VideoPipeline::handleSinkProbe(GstPadProbeInfo* info) {
    if (GST_PAD_PROBE_INFO_TYPE(info) & GST_PAD_PROBE_TYPE_BUFFER) {
        GstBuffer* buf = GST_PAD_PROBE_INFO_BUFFER(info);
        const std::uint64_t now = monotonicUs();
        const GstClockTime pts = GST_BUFFER_PTS(buf);
        std::optional<std::uint32_t> e2e;
        {
            std::lock_guard lock(m_mutex);
            ++m_stats.framesRendered;
            // Bu karede gecikme deseni okunduysa uçtan uca süre (tablette çizim → tahtada çizim).
            while (!m_patternByPts.empty() && m_patternByPts.front().first < pts)
                m_patternByPts.pop_front();
            if (!m_patternByPts.empty() && m_patternByPts.front().first == pts && m_offsetUs) {
                const std::int64_t d = std::int64_t(now) - (std::int64_t(m_patternByPts.front().second) + *m_offsetUs);
                m_patternByPts.pop_front();
                if (d > 0 && d < 10'000'000) {
                    e2e = std::uint32_t(d);
                    m_stats.e2eSumUs += std::uint64_t(d);
                    ++m_stats.e2eCount;
                }
            }
            if (GST_CLOCK_TIME_IS_VALID(pts)) {
                while (!m_inFlight.empty() && m_inFlight.front() <= pts)
                    m_inFlight.pop_front();
                if (m_basePtsUs && m_offsetUs) {
                    const std::int64_t senderUs = std::int64_t(*m_basePtsUs + pts / 1000);
                    const std::int64_t c2d = std::int64_t(now) - (senderUs + *m_offsetUs);
                    if (c2d >= 0) {
                        m_stats.c2dSumUs += std::uint64_t(c2d);
                        ++m_stats.c2dCount;
                    }
                }
            }
        }
        if (e2e) {
            std::lock_guard lock(m_e2eMutex);
            m_e2eSamples.push_back(*e2e);
            if (m_e2eSamples.size() > kMaxE2eSamples)
                m_e2eSamples.pop_front();
            m_e2eSessionSumUs += *e2e;
            ++m_e2eSessionCount;
        }
        ++m_totalRendered;
        if (!m_firstRendered.exchange(true))
            QMetaObject::invokeMethod(this, &VideoPipeline::firstFrameRendered, Qt::QueuedConnection);
        return;
    }

    GstEvent* event = GST_PAD_PROBE_INFO_EVENT(info);
    if (!event || GST_EVENT_TYPE(event) != GST_EVENT_CAPS)
        return;
    GstCaps* caps = nullptr;
    gst_event_parse_caps(event, &caps);
    const GstStructure* s = caps ? gst_caps_get_structure(caps, 0) : nullptr;
    int w = 0, h = 0;
    if (!s || !gst_structure_get_int(s, "width", &w) || !gst_structure_get_int(s, "height", &h))
        return;
    {
        std::lock_guard lock(m_mutex);
        if (w == m_width && h == m_height)
            return;
        m_width = w;
        m_height = h;
    }
    QMetaObject::invokeMethod(
        this, [this, w, h] { emit videoSizeChanged(w, h); }, Qt::QueuedConnection);
}

void VideoPipeline::handleDecodedProbe(GstPadProbeInfo* info) {
    if (GST_PAD_PROBE_INFO_TYPE(info) & GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM) {
        GstEvent* event = GST_PAD_PROBE_INFO_EVENT(info);
        if (!event || GST_EVENT_TYPE(event) != GST_EVENT_CAPS)
            return;
        GstCaps* caps = nullptr;
        gst_event_parse_caps(event, &caps);
        std::lock_guard lock(m_mutex);
        if (!m_decodedInfo)
            m_decodedInfo = gst_video_info_new();
        m_decodedInfoValid = caps && gst_video_info_from_caps(m_decodedInfo, caps);
        // VAMemory/DMABuf gibi özel bellek türleri ucuza okunamaz: ölçüm yalnız sistem belleğinde.
        const GstCapsFeatures* f = caps ? gst_caps_get_features(caps, 0) : nullptr;
        m_decodedSysmem = !f || gst_caps_features_is_any(f) ||
                          gst_caps_features_contains(f, GST_CAPS_FEATURE_MEMORY_SYSTEM_MEMORY);
        return;
    }
    GstBuffer* buf = GST_PAD_PROBE_INFO_BUFFER(info);
    if (!buf || !GST_BUFFER_PTS_IS_VALID(buf))
        return;
    GstVideoInfo vinfo;
    {
        std::lock_guard lock(m_mutex);
        if (!m_decodedInfoValid || !m_decodedSysmem || !GST_VIDEO_INFO_IS_YUV(m_decodedInfo))
            return;
        vinfo = *m_decodedInfo;
    }
    GstVideoFrame frame;
    if (!gst_video_frame_map(&frame, &vinfo, buf, GST_MAP_READ))
        return;
    const auto ts = LatencyPattern::decodeLuma(static_cast<const std::uint8_t*>(GST_VIDEO_FRAME_PLANE_DATA(&frame, 0)),
                                               GST_VIDEO_FRAME_WIDTH(&frame), GST_VIDEO_FRAME_HEIGHT(&frame),
                                               GST_VIDEO_FRAME_PLANE_STRIDE(&frame, 0));
    gst_video_frame_unmap(&frame);
    if (!ts)
        return;
    std::lock_guard lock(m_mutex);
    m_patternByPts.emplace_back(GST_BUFFER_PTS(buf), *ts);
    while (m_patternByPts.size() > 16)
        m_patternByPts.pop_front();
}

std::uint64_t VideoPipeline::e2eP95Us() const {
    std::lock_guard lock(m_e2eMutex);
    if (m_e2eSamples.empty())
        return 0;
    std::vector<std::uint32_t> v(m_e2eSamples.begin(), m_e2eSamples.end());
    const std::size_t k = std::min(v.size() - 1, v.size() * 95 / 100);
    std::nth_element(v.begin(), v.begin() + std::ptrdiff_t(k), v.end());
    return v[k];
}

std::uint64_t VideoPipeline::e2eAvgUs() const {
    std::lock_guard lock(m_e2eMutex);
    return m_e2eSessionCount ? m_e2eSessionSumUs / m_e2eSessionCount : 0;
}

VideoPipeline::Stats VideoPipeline::takeStats() {
    std::lock_guard lock(m_mutex);
    Stats s = m_stats;
    s.backlog = std::uint16_t(std::min<std::size_t>(m_inFlight.size(), 0xFFFF));
    m_stats = {};
    return s;
}

} // namespace yansit::media
