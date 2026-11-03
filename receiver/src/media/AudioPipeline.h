#pragma once

#include "protocol/Frame.h"

#include <QObject>
#include <QTimer>

#include <atomic>
#include <deque>
#include <cstdint>
#include <mutex>
#include <optional>

typedef struct _GstElement GstElement;
typedef struct _GstPadProbeInfo GstPadProbeInfo;

namespace yansit::media {

/**
 * Ses boru hattı: appsrc → opusdec | avdec_aac → audioconvert → audioresample → ses aygıtı (başsız: fakesink).
 *
 * Videoyla dudak senkronu (PLAN M5): her paket, göndericideki yakalama anı + saat ofseti + [targetLatencyUs]
 * anında çalınır. Hedef gecikme, videonun yakalama→çizim gecikmesine (bir ekran karesi eklenerek) eşitlenir;
 * zamanında çalınamayacak paketler atılır ve "geç" sayılır.
 */
class AudioPipeline : public QObject {
    Q_OBJECT

public:
    enum class SinkMode { Device, Fake };

    static constexpr std::uint64_t kMinTargetLatencyUs = 40'000;
    static constexpr std::uint64_t kMaxTargetLatencyUs = 250'000;

    struct Stats {
        std::uint32_t received = 0;
        std::uint32_t played = 0;
        std::uint32_t late = 0;
        std::uint64_t latencySumUs = 0;
        std::uint32_t latencyCount = 0;
    };

    explicit AudioPipeline(SinkMode mode, QObject* parent = nullptr);
    ~AudioPipeline() override;

    /** Çözücü ve çıkış öğeleri kurulu mu (WELCOME audio_enabled ve audio_decoders için). */
    static bool opusAvailable(SinkMode mode);
    static bool aacAvailable(SinkMode mode);

    void reset();
    void stop();
    void setClockOffset(std::optional<std::int64_t> offsetUs);
    /**
     * Video gecikmesine göre çağrılır. Asıl hedef = max(görüntü gecikmesi, sesin kendi varış gecikmesinin p95'i
     * + pay): ses görüntüden önce çalınabilecekse görüntüye hizalanır; ses yolu daha yavaşsa paketler geç kalmasın
     * diye sesin varış gecikmesine göre ayarlanır (ses görüntüden biraz sonra gelir; algıda daha az rahatsız edicidir).
     * Sonuç [kMinTargetLatencyUs, kMaxTargetLatencyUs] aralığına sıkıştırılır.
     */
    void setTargetLatencyUs(std::uint64_t videoUs);
    std::uint64_t targetLatencyUs() const { return m_targetUs.load(); }

    void pushFrame(const protocol::FrameHeader& header, protocol::Bytes&& payload, std::uint64_t recvUs);

    Stats takeStats();
    std::uint32_t totalReceived() const { return m_totalReceived; }
    std::uint32_t totalPlayed() const { return m_totalPlayed.load(); }
    std::uint32_t totalLate() const { return m_totalLate; }

    /// İç kullanım: sink pad sondası.
    void handleSinkProbe(GstPadProbeInfo* info);

private:
    bool build(std::uint8_t codec, const protocol::Bytes& config);
    void teardown();
    void pollBus();

    SinkMode m_mode;
    GstElement* m_pipeline = nullptr;
    GstElement* m_appsrc = nullptr;
    QTimer m_busTimer;
    std::uint8_t m_codec = 0;
    protocol::Bytes m_config;
    std::atomic<std::uint64_t> m_targetUs{kMinTargetLatencyUs};
    std::deque<std::uint32_t> m_arrivalUs; ///< son paketlerin yakalama→varış süresi (yalnız GUI iş parçacığı)
    std::atomic<std::uint64_t> m_appliedLatencyNs{0};

    std::mutex m_mutex;
    std::optional<std::int64_t> m_offsetUs;
    Stats m_stats;
    std::uint32_t m_totalReceived = 0;
    std::uint32_t m_totalLate = 0;
    std::atomic<std::uint32_t> m_totalPlayed{0};
};

} // namespace yansit::media
