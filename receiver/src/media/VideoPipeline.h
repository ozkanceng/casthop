#pragma once

#include "protocol/Frame.h"

#include <QObject>
#include <QPointer>
#include <QString>
#include <QTimer>

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

typedef struct _GstElement GstElement;
typedef struct _GstPadProbeInfo GstPadProbeInfo;

namespace yansit::media {

struct DecoderChoice {
    std::string factory;
    bool hardware = false;
};

/// Çalışma anında kullanılabilir çözücüler (codec: `Codec::H264` / `Codec::H265`), tercih sırasıyla (donanım önce).
/// `YANSIT_DECODER` (H.264) / `YANSIT_DECODER_H265` ortam değişkenleri listeyi tek bir fabrikaya indirir.
std::vector<DecoderChoice> availableDecoders(std::uint8_t codec);

/**
 * appsrc → h264parse|h265parse → çözücü → (GL: glupload → glcolorconvert → qml6glsink | başsız: fakesink).
 * sync=false: kare geldiği anda çizilir. Gecikme birikirse anahtar kareye kadar kare atılır ve
 * göndericiden IDR istenir. Donanım çözücü hata verirse yazılım çözücüye düşülür.
 */
class VideoPipeline : public QObject {
    Q_OBJECT

public:
    enum class SinkMode { Gl, Fake };

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
    };

    explicit VideoPipeline(SinkMode mode, QObject* parent = nullptr);
    ~VideoPipeline() override;

    /// GL modunda qml6glsink'in çizeceği GstGLQt6VideoItem.
    void setVideoItem(QObject* item) { m_videoItem = item; }

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

    /// İç kullanım: sink pad sondası (akış iş parçacığında çağrılır).
    void handleSinkProbe(GstPadProbeInfo* info);

signals:
    /// Göndericiden anahtar kare istenmeli (KeyframeReason).
    void keyframeNeeded(std::uint8_t reason);
    /// Oturumun ilk karesi çizildi.
    void firstFrameRendered();
    void videoSizeChanged(int width, int height);
    void decoderChanged();

private:
    bool build();
    void teardown();
    void pollBus();
    void dropFrame();
    void requestKeyframe(std::uint8_t reason);
    void onDecoderFailure(const QString& message);

    SinkMode m_mode;
    QPointer<QObject> m_videoItem;
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
    std::atomic<std::uint32_t> m_totalRendered{0};
    std::atomic<bool> m_firstRendered{false};
    std::uint32_t m_totalDropped = 0;
    std::uint32_t m_totalReceived = 0;
    int m_width = 0;
    int m_height = 0;
};

} // namespace yansit::media
