#include "app/DiagnosticLog.h"
#include "app/Platform.h"
#include "app/QrImageProvider.h"
#include "app/RenderCheck.h"
#include "app/ReceiverService.h"
#include "app/ReceiverSettings.h"
#include "app/ScreenshotTool.h"
#include "media/CpuVideoItem.h"
#include "media/VideoPipeline.h"
#include "protocol/Control.h"

#include <gst/gst.h>

#include <QCommandLineParser>
#include <QCoreApplication>
#include <QDir>
#include <QGuiApplication>
#include <QHostInfo>
#include <QLocale>
#include <QLoggingCategory>
#include <QOpenGLContext>
#include <QOpenGLFunctions>
#include <QQmlEngine>
#include <QQmlApplicationEngine>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QScreen>
#include <QStandardPaths>
#include <QTimer>
#include <QTranslator>
#include <QUrl>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <optional>

Q_LOGGING_CATEGORY(lcMain, "yansit.main")

using yansit::ReceiverService;
using SinkMode = yansit::media::VideoPipeline::SinkMode;

namespace {

/// A signal or console event sends BYE and closes down in order.
void installQuitOnSignal(ReceiverService& service) {
    yansit::platform::onQuitRequested(&service, [&service] {
        service.shutdown();
        // Kapanış çerçevelerinin gönderilmesi için kısa süre tanı.
        QTimer::singleShot(300, qApp, &QCoreApplication::quit);
    });
}

bool hasArg(int argc, char* argv[], const char* name) {
    for (int i = 1; i < argc; ++i)
        if (std::strcmp(argv[i], name) == 0)
            return true;
    return false;
}

bool glSinkInstalled() {
    GstElementFactory* f = gst_element_factory_find("qml6glsink");
    if (!f)
        return false;
    gst_object_unref(f);
    return true;
}

/// Loading the plugin registers the GstGLQt6VideoItem QML type. On Windows the factory can exist while the plugin
/// cannot load, for example when it was built against another Qt.
bool loadGlSink() {
    GstElement* probe = gst_element_factory_make("qml6glsink", nullptr);
    if (!probe)
        return false;
    gst_object_ref_sink(probe);
    gst_object_unref(probe);
    return true;
}

bool glVideoPossible() { return qEnvironmentVariable("QT_QUICK_BACKEND") != QLatin1String("software") && glSinkInstalled(); }

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
    yansit::platform::attachParentConsole();
    QCoreApplication app(argc, argv);
    setAppInfo();
    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("CastHop — akıllı tahta alıcısı / whiteboard receiver"));
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

void logEnvironment() {
    qCInfo(lcMain).noquote() << "CastHop" << QCoreApplication::applicationVersion() << "platform"
                             << QGuiApplication::platformName() << "oturum" << qEnvironmentVariable("XDG_SESSION_TYPE")
                             << "masaüstü" << qEnvironmentVariable("XDG_CURRENT_DESKTOP");
    guint major = 0, minor = 0, micro = 0, nano = 0;
    gst_version(&major, &minor, &micro, &nano);
    qCInfo(lcMain).noquote() << "Qt" << qVersion() << "GStreamer" << QStringLiteral("%1.%2.%3").arg(major).arg(minor).arg(micro);
    const bool installed = glSinkInstalled();
    qCInfo(lcMain) << "qml6glsink eklentisi:" << (installed ? "var" : "yok");
    if (!installed)
        qCInfo(lcMain).noquote() << "qml6glsink yüklenemedi:" << yansit::pluginLoadError(yansit::platform::gstPluginFile("qml6"));
    for (const std::uint8_t codec : {yansit::protocol::Codec::H264, yansit::protocol::Codec::H265})
        for (const auto& d : yansit::media::availableDecoders(codec))
            qCInfo(lcMain).noquote() << "çözücü:" << QString::fromStdString(d.factory) << (d.hardware ? "(donanım)" : "(yazılım)");
}

/// Logs the GL driver, which black screen reports need. Runs on the render thread.
void logGraphics(QQuickWindow& window) {
    QObject::connect(
        &window, &QQuickWindow::sceneGraphInitialized, &window,
        [&window] {
            const QOpenGLContext* context = QOpenGLContext::currentContext();
            if (!context) {
                qCInfo(lcMain) << "sahne grafiği: GL yok, API" << int(window.rendererInterface()->graphicsApi());
                return;
            }
            QOpenGLFunctions* f = context->functions();
            qCInfo(lcMain) << "GL:" << reinterpret_cast<const char*>(f->glGetString(GL_VENDOR)) << "/"
                           << reinterpret_cast<const char*>(f->glGetString(GL_RENDERER)) << "/"
                           << reinterpret_cast<const char*>(f->glGetString(GL_VERSION));
        },
        Qt::DirectConnection);
}

int runGui(int argc, char* argv[]) {
    const bool glPlugin = glVideoPossible();
    if (glPlugin)
        QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL); // qml6glsink OpenGL ister

    QGuiApplication app(argc, argv);
    setAppInfo();
    yansit::installDiagnosticLog();
    logEnvironment();
    QGuiApplication::setApplicationDisplayName(QStringLiteral("CastHop"));
    QGuiApplication::setDesktopFileName(QStringLiteral("io.github.ozkanceng.yansit.receiver"));

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("CastHop — akıllı tahta alıcısı / whiteboard receiver"));
    parser.addHelpOption();
    parser.addVersionOption();
    const CommonOptions o;
    o.addTo(parser);
    parser.process(app);

    auto opt = serviceOptions(parser, o);
    if (!opt)
        return 2;
    // Kalıcı ayarlar; komut satırı seçenekleri bu çalışma için önceliklidir.
    yansit::ReceiverSettings settings;
    // The UI never uses a sink that draws nothing: without GL, frames are drawn on the CPU.
    bool gl = glPlugin && !settings.compatibleVideo() && qEnvironmentVariable("YANSIT_RENDERER") != QLatin1String("cpu");
    if (gl && !loadGlSink()) {
        qCInfo(lcMain) << "qml6glsink yüklenemedi, CPU çizim kullanılıyor";
        gl = false;
    }
    opt->sinkMode = gl ? SinkMode::Gl : SinkMode::Cpu;
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

    ReceiverService service(*opt);
    installQuitOnSignal(service);
    QObject::connect(&app, &QCoreApplication::aboutToQuit, &service, &ReceiverService::shutdown);
    QObject::connect(&settings, &yansit::ReceiverSettings::changed, &service, [&] {
        if (!parser.isSet(o.name))
            service.setReceiverName(settings.boardName());
        if (!parser.isSet(o.noAudio))
            service.setAudioEnabled(settings.audioEnabled());
        if (settings.compatibleVideo())
            service.switchToCpuRenderer(QStringLiteral("uyumlu görüntü modu açıldı"));
    });
    yansit::ScreenshotTool screenshots;

    qmlRegisterType<yansit::media::CpuVideoItem>("Yansit.Video", 1, 0, "CpuVideoItem");

    auto engine = std::make_unique<QQmlApplicationEngine>();
    // Qt 6.4: qt_add_qml_module modülü qrc:/Yansit/ altına koyar ama "qrc:/" varsayılan içe aktarma yollarında yok.
    // Derleme dizininde uygulama dizinindeki Yansit/qmldir bulunduğu için fark edilmez; kurulu (/usr/bin) ikilide
    // qmldir bulunamaz ve Theme tekili tanımsız kalır.
    engine->addImportPath(QStringLiteral("qrc:/"));
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
    auto* window = qobject_cast<QQuickWindow*>(engine->rootObjects().constFirst());
    std::unique_ptr<yansit::RenderCheck> renderCheck;
    if (window) {
        logGraphics(*window);
        renderCheck = std::make_unique<yansit::RenderCheck>(service, *window);
    }
    // Arayüz yüklendikten sonra dinlemeye başla: el sıkışmaları yükleme sırasında bekletilmesin.
    // Hata olsa da arayüz açık kalır ve sorunu anlaşılır biçimde gösterir.
    service.start();
    const int rc = QGuiApplication::exec();
    renderCheck.reset();

    // Pencere ve sahne grafiği kaynakları (QR dokusu, görüntü önbelleği) deleteLater ile bırakılır;
    // olay döngüsü bittikten sonra bekleyen silmeleri işleyerek düzgün kapan.
    engine.reset();
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
    return rc;
}

} // namespace

int main(int argc, char* argv[]) {
    yansit::platform::prepareRuntime();
    gst_init(&argc, &argv);
    const int rc = hasArg(argc, argv, "--headless") ? runHeadless(argc, argv) : runGui(argc, argv);
    gst_deinit();
    return rc;
}
