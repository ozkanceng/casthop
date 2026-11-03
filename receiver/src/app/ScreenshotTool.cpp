#include "app/ScreenshotTool.h"

#include <QDateTime>
#include <QDir>
#include <QImage>
#include <QLoggingCategory>
#include <QQuickWindow>
#include <QStandardPaths>

Q_LOGGING_CATEGORY(lcShot, "yansit.screenshot")

namespace yansit {

QString ScreenshotTool::save(QObject* window) {
    auto* w = qobject_cast<QQuickWindow*>(window);
    if (!w)
        return {};
    // Sahne grafiğinin son karesi (video dokusu dahil).
    const QImage image = w->grabWindow();
    if (image.isNull()) {
        qCWarning(lcShot) << "pencere görüntüsü alınamadı";
        return {};
    }
    QString base = QStandardPaths::writableLocation(QStandardPaths::PicturesLocation);
    if (base.isEmpty())
        base = QDir::homePath();
    const QString dir = base + QStringLiteral("/Yansıt");
    QDir().mkpath(dir);
    const QString path =
        dir + QStringLiteral("/yansit-%1.png").arg(QDateTime::currentDateTime().toString(QStringLiteral("yyyyMMdd-HHmmss")));
    if (!image.save(path, "PNG")) {
        qCWarning(lcShot) << "kaydedilemedi:" << path;
        return {};
    }
    qCInfo(lcShot) << "kaydedildi:" << path;
    return path;
}

} // namespace yansit
