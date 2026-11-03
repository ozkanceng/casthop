#pragma once

#include <QQuickImageProvider>

namespace yansit {

/// `image://qr/<metnin onaltılık baytları>` → QR kodu (libqrencode, hata düzeltme M, 4 modül sessiz bölge).
class QrImageProvider : public QQuickImageProvider {
public:
    QrImageProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}

    QImage requestImage(const QString& id, QSize* size, const QSize& requestedSize) override;
};

/// Metni QR modül matrisine kodlar; her modül `scale` piksel. Başarısızsa boş görüntü.
QImage renderQr(const QByteArray& text, int scale);

} // namespace yansit
