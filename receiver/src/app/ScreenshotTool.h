#pragma once

#include <QObject>
#include <QString>

namespace yansit {

/// Tahtadaki görüntünün ekran görüntüsünü `~/Resimler/CastHop/` altına PNG olarak kaydeder.
class ScreenshotTool : public QObject {
    Q_OBJECT

public:
    using QObject::QObject;

    /// @param window QML ApplicationWindow (QQuickWindow). Başarısızsa boş dize.
    Q_INVOKABLE QString save(QObject* window);
};

} // namespace yansit
