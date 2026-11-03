# Üçüncü taraf bileşenler / Third-party components

Yansıt'ın kendi kodu [Apache-2.0](../LICENSE) lisanslıdır. Depoya üçüncü taraf kaynak kodu kopyalanmamıştır; aşağıdaki
bileşenler derleme veya çalışma zamanında paket yöneticisinden (Debian/Pardus deposu, Maven Central, Google Maven)
alınır. Bu liste RISKS R19 gereği tutulur ve bağımlılık eklendikçe güncellenir.

## Tahta alıcısı (`receiver/`)

| Bileşen | Kullanım | Lisans | Bağlantı biçimi |
|---|---|---|---|
| Qt 6 (Core, Gui, Network, Qml, Quick, Controls) | arayüz, ağ, TLS | LGPL-3.0 | dinamik (.deb: sistem kütüphanesi) |
| GStreamer 1.22 + plugins-base/good/bad, gst-libav, gstreamer1.0-qt6, gstreamer1.0-gl | video/ses çözme ve çizim | LGPL-2.1+ | dinamik / eklenti |
| FFmpeg (libavcodec, gst-libav üzerinden) | yazılım H.264/H.265/AAC çözme | LGPL-2.1+ (Debian derlemesi) | dinamik |
| OpenSSL 3 | HMAC-SHA256, sertifika üretimi, TLS (Qt arka ucu) | Apache-2.0 | dinamik |
| libqrencode | QR kodu | LGPL-2.1+ | dinamik |
| Avahi (libavahi-client) | mDNS yayını | LGPL-2.1+ | dinamik |
| Noto Sans / Noto Sans Mono | yazı tipi (paket bağımlılığı) | SIL OFL 1.1 | sistem yazı tipi |
| GoogleTest | yalnız birim testleri | BSD-3-Clause | derleme zamanı |
| nlohmann/json | yalnız birim testleri | MIT | derleme zamanı |

AppImage (taşınabilir sürüm) Qt ve GStreamer kütüphanelerini paylaşımlı kütüphane olarak içerir; LGPL gereği
kullanıcı bunları değiştirebilir (`--appimage-extract` ile açılıp `usr/lib` altındaki dosyalar değiştirilebilir).
Gömülü kütüphanelerin kaynak kodu Debian 12 kaynak paketlerinde (`apt-get source <paket>`) bulunur; sürümler AppImage
içindeki `usr/share/yansit/bundled-packages.txt` dosyasında listelenir.

## Tablet uygulaması (`android/`)

| Bileşen | Kullanım | Lisans |
|---|---|---|
| Kotlin standart kitaplığı, kotlinx.coroutines | dil, eşzamanlılık | Apache-2.0 |
| AndroidX (core, activity, lifecycle), Jetpack Compose, Material 3 | arayüz | Apache-2.0 |
| CameraX (camera2, lifecycle, view) | QR tarama kamerası | Apache-2.0 |
| zxing-cpp (`io.github.zxing-cpp:android`) | QR çözme | Apache-2.0 |
| kotlinx.serialization (json) | yalnız testler ve `tools/fake-sender` | Apache-2.0 |
| JUnit 5 | yalnız testler | EPL-2.0 |

## Araçlar ve CI

`tools/integration` ve `tools/soak` test videolarını çalışma anında GStreamer'ın `x264enc`/`x265enc`/`opusenc`/
`avenc_aac` öğeleriyle üretir (gstreamer1.0-plugins-ugly/bad, GPL/LGPL); bu öğeler yalnız CI ortamında kullanılır,
dağıtılan paketlere girmez.
