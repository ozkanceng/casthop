#include "app/QrImageProvider.h"
#include "app/ReceiverService.h"
#include "app/ReceiverSettings.h"
#include "app/ScreenshotTool.h"

#include <gst/gst.h>

#include <QCommandLineParser>
#include <QCoreApplication>
#include <QDir>
#include <QGuiApplication>
#include <QHostInfo>
#include <QLocale>
#include <QLoggingCategory>
#include <QQmlApplicationEngine>
#include <QQuickWindow>
#include <QScreen>
#include <QSocketNotifier>
#include <QStandardPaths>
#include <QTimer>
#include <QTranslator>
#include <QUrl>

#include <algorithm>
#include <csignal>
#include <cstring>
#include <memory>
#include <optional>
#include <unistd.h>

using yansit::ReceiverService;
using SinkMode = yansit::media::VideoPipeline::SinkMode;

namespace {

int g_signalPipe[2] = {-1, -1};

void onSignal(int) {
    const char c = 1;
    [[maybe_unused]] const auto n = ::write(g_signalPipe[1], &c, 1);
}

/// SIGINT/SIGTERM'de BYE gönderip düzgün kapanmak için (self-pipe).
void installQuitOnSignal(ReceiverService& service) {
    if (::pipe(g_signalPipe) != 0)
        return;
    auto* notifier = new QSocketNotifier(g_signalPipe[0], QSocketNotifier::Read, &service);
    QObject::connect(notifier, &QSocketNotifier::activated, &service, [&service, notifier] {
        char c;
        [[maybe_unused]] const auto n = ::read(g_signalPipe[0], &c, 1);
        notifier->setEnabled(false);
        service.shutdown();
        // Kapanış çerçevelerinin gönderilmesi için kısa süre tanı.
        QTimer::singleShot(300, qApp, &QCoreApplication::quit);
    });
    struct sigaction sa {};
    sa.sa_handler = onSignal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
}

bool hasArg(int argc, char* argv[], const char* name) {
    for (int i = 1; i < argc; ++i)
        if (std::strcmp(argv[i], name) == 0)
            return true;
    return false;
}

bool glVideoPossible() {
    if (qEnvironmentVariable("QT_QUICK_BACKEND") == QLatin1String("software"))
        return false;
    GstElementFactory* f = gst_element_factory_find("qml6glsink");
    if (!f)
        return false;
    gst_object_unref(f);
    return true;
}

struct CommonOptions {
    QCommandLineOption port{QStringLiteral("port"), QStringLiteral("TCP port (default 47800)"), QStringLiteral("port"),
                            QStringLiteral("47800")};
    // Not: "--name" Qt'nin X11 seçeneğidir (QGuiApplication tüketir); bu yüzden "--board-name".
    QCommandLineOption name{QStringLiteral("board-name"), QStringLiteral("Board name shown on tablets"),
                            QStringLiteral("name")};
    QCommandLineOption dataDir{QStringLiteral("data-dir"),
                               QStringLiteral("Directory for certificate and pairings"), QStringLiteral("dir")};
    QCommandLineOption headless{QStringLiteral("headless"),
                                QStringLiteral("No window: decode to a null sink (automated tests)")};
    QCommandLineOption pairingFile{QStringLiteral("pairing-file"),
                                   QStringLiteral("Write QR URI and PIN to this JSON file (automated tests)"),
                                   QStringLiteral("file")};
    QCommandLineOption statsFile{QStringLiteral("stats-file"),
                                 QStringLiteral("Append per-session statistics (JSON Lines)"), QStringLiteral("file")};
    QCommandLineOption approvalTimeout{QStringLiteral("approval-timeout"),
                                       QStringLiteral("Takeover approval timeout in ms (default 20000)"),
                                       QStringLiteral("ms"), QStringLiteral("20000")};
    QCommandLineOption qrHost{QStringLiteral("qr-host"),
                              QStringLiteral("Address to put into the QR code (repeatable; default: auto)"),
                              QStringLiteral("ip")};
    QCommandLineOption verbose{QStringLiteral("verbose"), QStringLiteral("Detailed log output")};
    QCommandLineOption noAudio{QStringLiteral("no-audio"), QStringLiteral("Do not play the tablet's sound")};
    QCommandLineOption windowed{QStringLiteral("windowed"), QStringLiteral("Start windowed instead of full screen")};
    QCommandLineOption exitAfter{QStringLiteral("exit-after"),
                                 QStringLiteral("Quit after N ms (automated tests)"), QStringLiteral("ms")};

    void addTo(QCommandLineParser& p) const {
        p.addOptions({port, name, dataDir, headless, pairingFile, statsFile, approvalTimeout, qrHost, verbose,
                      noAudio, windowed, exitAfter});
    }
};

/// Seçenekleri ayrıştırır; hata durumunda mesaj yazar ve nullopt döner.
std::optional<ReceiverService::Options> serviceOptions(const QCommandLineParser& p, const CommonOptions& o) {
    ReceiverService::Options opt;
    bool ok = false;
    const uint port = p.value(o.port).toUInt(&ok);
    if (!ok || port == 0 || port > 65535) {
        qCritical("--port: geçersiz değer");
        return std::nullopt;
    }
    opt.port = quint16(port);
    const int approval = p.value(o.approvalTimeout).toInt(&ok);
    if (!ok || approval < 1000 || approval > 120000) {
        qCritical("--approval-timeout: 1000–120000 ms olmalı");
        return std::nullopt;
    }
    opt.approvalTimeout = std::chrono::milliseconds(approval);
    opt.name = p.isSet(o.name) ? p.value(o.name) : QHostInfo::localHostName();
    opt.dataDir = p.isSet(o.dataDir) ? p.value(o.dataDir)
                                     : QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    opt.pairingFile = p.value(o.pairingFile);
    opt.statsFile = p.value(o.statsFile);
    opt.qrHosts = p.values(o.qrHost);
    opt.audio = !p.isSet(o.noAudio);
    return opt;
}

void applyCommonRuntime(const QCommandLineParser& p, const CommonOptions& o) {
    if (p.isSet(o.verbose))
        QLoggingCategory::setFilterRules(QStringLiteral("yansit.*.debug=true"));
    if (p.isSet(o.exitAfter)) {
        bool ok = false;
        const int ms = p.value(o.exitAfter).toInt(&ok);
        if (!ok || ms < 0) {
            qCritical("--exit-after: geçersiz değer");
            std::exit(2);
        }
        QTimer::singleShot(ms, qApp, &QCoreApplication::quit);
    }
}

void setAppInfo() {
    QCoreApplication::setApplicationName(QStringLiteral("yansit-receiver"));
    QCoreApplication::setApplicationVersion(QStringLiteral(YANSIT_VERSION));
    QCoreApplication::setOrganizationName(QStringLiteral("Yansit"));
}

int runHeadless(int argc, char* argv[]) {
    QCoreApplication app(argc, argv);
    setAppInfo();
    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("Yansıt — akıllı tahta alıcısı / whiteboard receiver"));
    parser.addHelpOption();
    parser.addVersionOption();
    const CommonOptions o;
    o.addTo(parser);
    parser.process(app);

    auto opt = serviceOptions(parser, o);
    if (!opt)
        return 2;
    opt->sinkMode = SinkMode::Fake;
    applyCommonRuntime(parser, o);

    ReceiverService service(*opt);
    if (!service.start())
        return 1;
    installQuitOnSignal(service);
    QObject::connect(&app, &QCoreApplication::aboutToQuit, &service, &ReceiverService::shutdown);
    return QCoreApplication::exec();
}

int runGui(int argc, char* argv[]) {
    const bool gl = glVideoPossible();
    if (gl)
        QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL); // qml6glsink OpenGL ister

    QGuiApplication app(argc, argv);
    setAppInfo();
    QGuiApplication::setApplicationDisplayName(QStringLiteral("Yansıt"));
    QGuiApplication::setDesktopFileName(QStringLiteral("io.github.ozkanceng.yansit.receiver"));

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("Yansıt — akıllı tahta alıcısı / whiteboard receiver"));
    parser.addHelpOption();
    parser.addVersionOption();
    const CommonOptions o;
    o.addTo(parser);
    parser.process(app);

    auto opt = serviceOptions(parser, o);
    if (!opt)
        return 2;
    opt->sinkMode = gl ? SinkMode::Gl : SinkMode::Fake;
    // Kalıcı ayarlar; komut satırı seçenekleri bu çalışma için önceliklidir.
    yansit::ReceiverSettings settings;
    if (!parser.isSet(o.name))
        opt->name = settings.boardName();
    if (!parser.isSet(o.port))
        opt->port = quint16(settings.port());
    if (!parser.isSet(o.noAudio))
        opt->audio = settings.audioEnabled();
    if (const QScreen* screen = QGuiApplication::primaryScreen()) {
        const QSize px = screen->size() * screen->devicePixelRatio();
        opt->displayWidth = std::uint16_t(std::clamp(px.width(), 1, 0xFFFF));
        opt->displayHeight = std::uint16_t(std::clamp(px.height(), 1, 0xFFFF));
        opt->displayRefresh = std::uint8_t(std::clamp(qRound(screen->refreshRate()), 1, 240));
    }
    applyCommonRuntime(parser, o);

    // Türkçe varsayılan; sistem dili İngilizce ise İngilizce.
    QTranslator translator;
    const QString lang = QLocale().language() == QLocale::English ? QStringLiteral("en") : QStringLiteral("tr");
    if (translator.load(QStringLiteral(":/i18n/yansit_%1.qm").arg(lang)))
        QGuiApplication::installTranslator(&translator);

    if (gl) {
        // qml6glsink eklentisinin yüklenmesi GstGLQt6VideoItem QML türünü kaydeder.
        GstElement* probe = gst_element_factory_make("qml6glsink", nullptr);
        if (probe) {
            gst_object_ref_sink(probe);
            gst_object_unref(probe);
        }
    }

    ReceiverService service(*opt);
    installQuitOnSignal(service);
    QObject::connect(&app, &QCoreApplication::aboutToQuit, &service, &ReceiverService::shutdown);
    QObject::connect(&settings, &yansit::ReceiverSettings::changed, &service, [&] {
        if (!parser.isSet(o.name))
            service.setReceiverName(settings.boardName());
        if (!parser.isSet(o.noAudio))
            service.setAudioEnabled(settings.audioEnabled());
    });
    yansit::ScreenshotTool screenshots;

    auto engine = std::make_unique<QQmlApplicationEngine>();
    engine->addImageProvider(QStringLiteral("qr"), new yansit::QrImageProvider);
    engine->setInitialProperties({{QStringLiteral("startFullScreen"), !parser.isSet(o.windowed)},
                                  {QStringLiteral("receiver"), QVariant::fromValue(&service)},
                                  {QStringLiteral("settings"), QVariant::fromValue(&settings)},
                                  {QStringLiteral("screenshots"), QVariant::fromValue(&screenshots)}});
    QObject::connect(
        engine.get(), &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
        Qt::QueuedConnection);
    // Qt 6.4: loadFromModule() yok (6.5+); qt_add_qml_module kaynak yolunu kullan.
    engine->load(QUrl(QStringLiteral("qrc:/Yansit/qml/Main.qml")));
    if (engine->rootObjects().isEmpty())
        return 1;
    // Arayüz yüklendikten sonra dinlemeye başla: el sıkışmaları yükleme sırasında bekletilmesin.
    // Hata olsa da arayüz açık kalır ve sorunu anlaşılır biçimde gösterir.
    service.start();
    const int rc = QGuiApplication::exec();

    // Pencere ve sahne grafiği kaynakları (QR dokusu, görüntü önbelleği) deleteLater ile bırakılır;
    // olay döngüsü bittikten sonra bekleyen silmeleri işleyerek düzgün kapan.
    engine.reset();
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    return rc;
}

} // namespace

int main(int argc, char* argv[]) {
    gst_init(&argc, &argv);
    const int rc = hasArg(argc, argv, "--headless") ? runHeadless(argc, argv) : runGui(argc, argv);
    gst_deinit();
    return rc;
}
