#include "app/RenderCheck.h"

#include "app/ReceiverService.h"
#include "media/VideoPipeline.h"

#include <QImage>
#include <QLoggingCategory>
#include <QQuickWindow>

Q_LOGGING_CATEGORY(lcCheck, "yansit.rendercheck")

namespace yansit {

namespace {

constexpr int kDelayMs = 2500;
constexpr int kMaxRuns = 4;
/// A window centre darker than this counts as undrawn (portrait video leaves black bars at the sides).
constexpr int kBlackWindowLuma = 4;

/// Mean grey (0-255) of the central half; the info bar sits at the edge and stays out of it.
int centreBrightness(const QImage& image) {
    const QImage rgb = image.convertToFormat(QImage::Format_RGB32);
    constexpr int kSteps = 32;
    qint64 sum = 0;
    for (int j = 0; j < kSteps; ++j)
        for (int i = 0; i < kSteps; ++i)
            sum += qGray(rgb.pixel(rgb.width() / 4 + i * (rgb.width() / 2) / kSteps,
                                   rgb.height() / 4 + j * (rgb.height() / 2) / kSteps));
    return int(sum / (kSteps * kSteps));
}

} // namespace

RenderCheck::RenderCheck(ReceiverService& service, QQuickWindow& window, QObject* parent)
    : QObject(parent), m_service(service), m_window(window) {
    m_timer.setSingleShot(true);
    m_timer.setInterval(kDelayMs);
    connect(&m_timer, &QTimer::timeout, this, &RenderCheck::run);
    connect(&m_service, &ReceiverService::stateChanged, this, &RenderCheck::onStateChanged);
}

void RenderCheck::onStateChanged() {
    const bool streaming = m_service.stateName() == QLatin1String("streaming");
    if (streaming && !m_streaming) {
        m_runs = 0;
        m_timer.start();
    } else if (!streaming) {
        m_timer.stop();
    }
    m_streaming = streaming;
}

void RenderCheck::run() {
    if (++m_runs > kMaxRuns)
        return;
    media::VideoPipeline& video = *m_service.video();
    const int decoded = video.decodedLuma();
    const QImage shot = m_window.grabWindow();
    int window = shot.isNull() ? -1 : centreBrightness(shot);
    if (qEnvironmentVariableIntValue("YANSIT_TEST_BLACK_GL") == 1 && m_service.renderer() == QLatin1String("gl"))
        window = 0; // tools/integration: simulates a board with broken GL rendering
    qCInfo(lcCheck) << "görüntü denetimi: çözülen parlaklık" << decoded << "pencere parlaklığı" << window
                    << "çizici" << m_service.renderer() << "çözücü" << video.decoderName();

    if (decoded < 0) {
        qCInfo(lcCheck) << "çözülen kare okunamadı, denetim yinelenecek";
        m_timer.start(); // without a measurement a black window would stay undetected
    } else if (decoded < media::VideoPipeline::kBlackLuma) {
        if (video.retryWithNextDecoder(QStringLiteral("donanım çözücü siyah kare üretiyor"))) {
            m_timer.start();
            return;
        }
        qCWarning(lcCheck) << "çözülen kareler siyah: tablet siyah görüntü gönderiyor olabilir";
        m_timer.start(); // the screen may really be dark; measure again later
    } else if (window >= 0 && window < kBlackWindowLuma) {
        if (m_service.renderer() == QLatin1String("gl")) {
            m_service.switchToCpuRenderer(QStringLiteral("kare dolu ama pencere siyah"));
            m_timer.start();
        } else {
            qCWarning(lcCheck) << "CPU çizimde de pencere siyah";
        }
    } else {
        qCInfo(lcCheck) << "görüntü çiziliyor";
    }
}

} // namespace yansit
