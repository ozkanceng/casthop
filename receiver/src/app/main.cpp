#include <QCommandLineParser>
#include <QGuiApplication>
#include <QLocale>
#include <QQmlApplicationEngine>
#include <QTimer>
#include <QTranslator>
#include <QUrl>

int main(int argc, char* argv[]) {
    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("yansit-receiver"));
    QGuiApplication::setApplicationDisplayName(QStringLiteral("Yansıt"));
    QGuiApplication::setApplicationVersion(QStringLiteral(YANSIT_VERSION));
    QGuiApplication::setOrganizationName(QStringLiteral("Yansit"));
    QGuiApplication::setDesktopFileName(QStringLiteral("io.github.ozkanceng.yansit.receiver"));

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("Yansıt — akıllı tahta alıcısı / whiteboard receiver"));
    parser.addHelpOption();
    parser.addVersionOption();
    QCommandLineOption windowedOpt(QStringLiteral("windowed"),
                                   QStringLiteral("Tam ekran yerine pencerede başlat / start windowed"));
    parser.addOption(windowedOpt);
    QCommandLineOption exitAfterOpt(QStringLiteral("exit-after"),
                                    QStringLiteral("Otomatik testler için: N ms sonra çık / exit after N ms"),
                                    QStringLiteral("ms"));
    parser.addOption(exitAfterOpt);
    parser.process(app);

    // Türkçe varsayılan; sistem dili İngilizce ise İngilizce.
    QTranslator translator;
    const QLocale locale;
    const QString lang = locale.language() == QLocale::English ? QStringLiteral("en") : QStringLiteral("tr");
    if (translator.load(QStringLiteral(":/i18n/yansit_%1.qm").arg(lang)))
        QGuiApplication::installTranslator(&translator);

    QQmlApplicationEngine engine;
    engine.setInitialProperties({{QStringLiteral("startFullScreen"), !parser.isSet(windowedOpt)}});
    QObject::connect(
        &engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
        Qt::QueuedConnection);
    // Qt 6.4: loadFromModule() yok (6.5+); qt_add_qml_module kaynak yolunu kullan.
    engine.load(QUrl(QStringLiteral("qrc:/Yansit/qml/Main.qml")));

    if (engine.rootObjects().isEmpty())
        return 1;

    if (parser.isSet(exitAfterOpt)) {
        bool ok = false;
        const int ms = parser.value(exitAfterOpt).toInt(&ok);
        if (!ok || ms < 0) {
            qCritical("--exit-after: geçersiz değer");
            return 2;
        }
        QTimer::singleShot(ms, &app, &QCoreApplication::quit);
    }

    return QGuiApplication::exec();
}
