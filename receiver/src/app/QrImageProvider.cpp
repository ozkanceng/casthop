#include "app/QrImageProvider.h"

#include <qrencode.h>

#include <algorithm>

namespace yansit {

namespace {
constexpr int kQuietZone = 4;
}

QImage renderQr(const QByteArray& text, int scale) {
    if (text.isEmpty() || scale < 1)
        return {};
    QRcode* code = QRcode_encodeString8bit(text.constData(), 0, QR_ECLEVEL_M);
    if (!code)
        return {};
    const int modules = code->width + 2 * kQuietZone;
    QImage img(modules * scale, modules * scale, QImage::Format_Grayscale8);
    img.fill(255);
    for (int y = 0; y < code->width; ++y) {
        for (int x = 0; x < code->width; ++x) {
            if (!(code->data[y * code->width + x] & 1))
                continue;
            for (int dy = 0; dy < scale; ++dy) {
                uchar* line = img.scanLine((y + kQuietZone) * scale + dy);
                std::fill_n(line + (x + kQuietZone) * scale, scale, uchar(0));
            }
        }
    }
    QRcode_free(code);
    return img;
}

QImage QrImageProvider::requestImage(const QString& id, QSize* size, const QSize& requestedSize) {
    const QByteArray text = QByteArray::fromHex(id.toLatin1());
    // Modül başına tam piksel: keskin kenarlar için ölçek tamsayı seçilir.
    const int target = std::max({requestedSize.width(), requestedSize.height(), 256});
    QImage probe = renderQr(text, 1);
    if (probe.isNull())
        return {};
    const int scale = std::max(1, target / probe.width());
    QImage img = renderQr(text, scale);
    if (size)
        *size = img.size();
    return img;
}

} // namespace yansit
