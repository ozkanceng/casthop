#pragma once

#include "media/RotationTimeline.h"
#include "protocol/Frame.h"

#include <QObject>
#include <QPointer>
#include <QString>
#include <QTimer>

#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

typedef struct _GstElement GstElement;
typedef struct _GstPadProbeInfo GstPadProbeInfo;
typedef struct _GstSample GstSample;
typedef struct _GstVideoInfo GstVideoInfo;

namespace yansit::media {

struct DecoderChoice {
    std::string factory;
    bool hardware = false;
};

/// Çalışma anında kullanılabilir çözücüler (codec: `Codec::H264` / `Codec::H265`), tercih sırasıyla (donanım önce).
/// `YANSIT_DECODER` (H.264) / `YANSIT_DECODER_H265` ortam değişkenleri listeyi tek bir fabrikaya indirir.
std::vector<DecoderChoice> availableDecoders(std::uint8_t codec);

/// Known decoders come from a table; anything else (`YANSIT_DECODER`) by the "Hardware" element class.
bool isHardwareDecoder(const std::string& factory);

/**
 * appsrc → h264parse|h265parse → çözücü → (GL: glupload → glcolorconvert → qml6glsink |
 * CPU: videoconvert → appsink | başsız: fakesink).
 * sync=false: kare geldiği anda çizilir. Gecikme birikirse anahtar kareye kadar kare atılır ve
 * göndericiden IDR istenir. Donanım çözücü hata verirse yazılım çözücüye düşülür.
 */
class VideoPipeline : public QObject {
    Q_OBJECT

public:
    /// Gl: drawn by the GPU. Cpu: RGBA frames go to a `FrameConsumer` (no GL context sharing).
    /// Fake: headless mode only; nothing is drawn.
    enum class SinkMode { Gl, Cpu, Fake };

    /// Called on the streaming thread; the sample stays owned by the caller (use gst_sample_ref to keep it).
    using FrameConsumer = std::function<void(GstSample*)>;

    /// Mean luma below this counts as black (limited-range Y: black is 16).
    static constexpr int kBlackLuma = 24;

    /// Çözücü önünde (gönderilmiş ama henüz çizilmemiş) olağan en fazla kare; bu sınır
    /// kBacklogGraceUs boyunca aşılırsa anahtar kareye kadar kare atılır.
    static constexpr std::size_t kMaxInFlight = 4;
    static constexpr std::uint64_t kBacklogGraceUs = 250'000;
    /// Her durumda aşılmayan sınır (60 fps'te ≈ 0,5 sn).
    static constexpr std::size_t kHardMaxInFlight = 30;
    /// İlk kare çizilmeden önceki sınır: GL bağlamı/çözücü kurulumu yavaş tahtalarda ≈ 0,5–1 sn sürebilir.
    static constexpr std::size_t kStartupMaxInFlight = 120;

    /// Bir rapor aralığının istatistikleri (takeStats ile sıfırlanır).
    struct Stats {
        std::uint32_t framesReceived = 0;
        std::uint32_t framesRendered = 0;
        std::uint32_t framesDropped = 0;
        std::uint64_t bytesReceived = 0;
        std::uint64_t c2rSumUs = 0;
        std::uint32_t c2rCount = 0;
        std::uint32_t c2rMinUs = 0;
        std::uint32_t c2rMaxUs = 0;
        std::uint64_t c2dSumUs = 0;
        std::uint32_t c2dCount = 0;
        std::uint16_t backlog = 0;
        /// Gecikme test deseni görüldüyse (tools/latency): tablette çizim → tahtada çizim.
        std::uint64_t e2eSumUs = 0;
        std::uint32_t e2eCount = 0;
    };

    explicit VideoPipeline(SinkMode mode, QObject* parent = nullptr);
    ~VideoPipeline() override;

    /// GL modunda qml6glsink'in çizeceği GstGLQt6VideoItem.
    void setVideoItem(QObject* item) { m_videoItem = item; }

    /// Renderer that receives frames in CPU mode; empty detaches. Rebuilds the pipeline if a session is running.
    void setFrameConsumer(FrameConsumer consumer);

    SinkMode sinkMode() const { return m_mode; }
    /// Drops the GL pipeline and enters CPU mode; streaming resumes once `setFrameConsumer` attaches a renderer.
    void useCpuSink();
    /// Moves from a hardware decoder to the next one; false if there is none.
    bool retryWithNextDecoder(const QString& reason);
    /// Mean luma (0-255) of the centre of the last sampled decoded frame; -1 if it could not be read.
    int decodedLuma() const { return m_decodedLuma.load(); }

    /// Yeni oturum: durum sıfırlanır; boru hattı ilk CONFIG ile kurulur.
    void reset();
    /// Boru hattını durdurur ve serbest bırakır.
    void stop();

    /// Gönderici saatinden alıcı saatine ofset (alıcı − gönderici, µs); bilinmiyorsa nullopt.
    void setClockOffset(std::optional<std::int64_t> offsetUs);

    void pushFrame(const protocol::FrameHeader& header, protocol::Bytes&& payload, std::uint64_t recvUs);

    Stats takeStats();

    QString decoderName() const { return QString::fromStdString(m_decoder.factory); }
    bool hardwareDecoding() const { return m_decoder.hardware; }
    std::uint32_t totalRendered() const { return m_totalRendered.load(); }
    std::uint32_t totalDropped() const { return m_totalDropped; }
    std::uint32_t totalReceived() const { return m_totalReceived; }
    /// A turned picture (FrameFlags::Rotated) reached the decoder in this session (stats file `video_rotated`).
    bool rotatedSeen() const { return m_rotatedSeen.load(); }

    /// Internal: hands an appsink sample to the renderer (streaming thread).
    void deliverSample(GstSample* sample);
    /// İç kullanım: sink pad sondası (akış iş parçacığında çağrılır).
    void handleSinkProbe(GstPadProbeInfo* info);
    /// İç kullanım: çözücü çıkışı sondası; gecikme desenini okur.
    void handleDecodedProbe(GstPadProbeInfo* info);

    /// Oturum boyunca ölçülen uçtan uca gecikmenin 95. yüzdeliği (µs); ölçüm yoksa 0.
    std::uint64_t e2eP95Us() const;
    std::uint64_t e2eAvgUs() const;

signals:
    /// Göndericiden anahtar kare istenmeli (KeyframeReason).
    void keyframeNeeded(std::uint8_t reason);
    /// Oturumun ilk karesi çizildi.
    void firstFrameRendered();
    void videoSizeChanged(int width, int height);
    /// The frame now on screen came turned 90° clockwise and must be shown turned back (or no longer).
    void videoRotatedChanged(bool rotated);
    void decoderChanged();
    /// The GL drawing chain failed; switch to CPU mode.
    void glRenderFailed(const QString& message);

private:
    bool build();
    void teardown();
    void pollBus();
    void dropFrame();
    void requestKeyframe(std::uint8_t reason);
    void onDecoderFailure(const QString& message);
    void restartFromConfig();

    SinkMode m_mode;
    QPointer<QObject> m_videoItem;
    std::mutex m_consumerMutex;
    FrameConsumer m_consumer;
    GstElement* m_pipeline = nullptr;
    GstElement* m_appsrc = nullptr;
    QTimer m_busTimer;

    std::uint8_t m_codec = 0;
    std::vector<DecoderChoice> m_decoders;
    std::size_t m_decoderIndex = 0;
    DecoderChoice m_decoder;

    protocol::Bytes m_config;
    bool m_configPending = false;
    bool m_waitKeyframe = true;
    std::optional<std::uint32_t> m_lastSeq;
    std::uint64_t m_lastBufferPtsNs = 0;
    std::uint64_t m_lastKeyframeRequestUs = 0;
    std::uint64_t m_lastRebuildUs = 0;
    std::uint64_t m_backlogSinceUs = 0;

    // Akış iş parçacığıyla paylaşılır.
    std::mutex m_mutex;
    Stats m_stats;
    std::optional<std::uint64_t> m_basePtsUs; ///< oturumun ilk karesinin gönderici PTS'i
    std::optional<std::int64_t> m_offsetUs;
    std::deque<std::uint64_t> m_inFlight; ///< appsrc'ye verilmiş, henüz sink'e ulaşmamış PTS'ler (ns)
    RotationTimeline m_turns;             ///< PTS (ns) → çevrili mi
    bool m_shownRotated = false;
    std::atomic<bool> m_rotatedSeen{false};
    std::atomic<std::uint32_t> m_totalRendered{0};
    std::atomic<bool> m_firstRendered{false};
    std::uint32_t m_totalDropped = 0;
    std::uint32_t m_totalReceived = 0;
    int m_width = 0;
    int m_height = 0;
    std::atomic<int> m_decodedLuma{-1};
    std::uint32_t m_lumaCountdown = 0;

    // Gecikme deseni (akış iş parçacıkları; m_mutex ile).
    GstVideoInfo* m_decodedInfo = nullptr;
    bool m_decodedInfoValid = false;
    bool m_decodedSysmem = false;
    std::deque<std::pair<std::uint64_t, std::uint64_t>> m_patternByPts; ///< (PTS ns, tablet damgası µs)
    mutable std::mutex m_e2eMutex;
    std::deque<std::uint32_t> m_e2eSamples; ///< son N ölçüm (µs), yüzdelik için
    std::uint64_t m_e2eSessionSumUs = 0;
    std::uint64_t m_e2eSessionCount = 0;
};

} // namespace yansit::media
