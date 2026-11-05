# Google Play materyalleri

- `listings/<dil>/`: başlık (≤30), kısa açıklama (≤80), uzun açıklama (≤4000). Dosya adları fastlane `supply` düzenine uyar.
- `graphics/icon-512.png`: `brand/casthop-icon-square.svg` dosyasından, `brand/render.swift` ile (`render in.svg out.png 512 512`).
- `graphics/<dil>/feature-graphic.png`: 1024×500, `graphics/feature-graphic.html?lang=tr|en` sayfasından 1024×500 görünümde alınan ekran görüntüsü (yazı tipi sistemden gelir, 24 bit, alfa yok).
- `graphics/<dil>/phone-*.png`: 1080×2100, Android 15 emülatörü (1080×2100, 420 dpi), hata ayıklama sürümü `-PyansitVersionName=<sürüm>` ile, temiz durumda (kayıtlı ekran yok).
- `graphics/<dil>/tablet10-*.png`: 2560×1418, gerçek tablet (SM-X610) ekran görüntüsünün durum ve görev çubuğu kırpılmış hâli (`magick in.png -crop 2560x1418+0+76 +repage out.png`).

Görüntülerde QR kod, PIN, bağlantı kodu, ekran adı, Wi-Fi adı veya token olmamalı. Yenilerken uygulamayı temiz kurulumla aç ve her kareyi göz ile denetle.

## Ön plan hizmeti videoları

Console, `mediaProjection` ve `camera` hizmetleri için YouTube bağlantısı ister. Videolar tabletten `adb shell screenrecord` ile alınır (ekran yansıtma + bildirim çubuğu + arka planda sürmesi; kamera yansıtma aynı şekilde), `play/video-review/` altına konur ve **depoya girmez** (`.gitignore`): görüntüde cihaz adları olur. YouTube'a «Liste dışı» yüklenir; inceleme bitene kadar silinmez.
