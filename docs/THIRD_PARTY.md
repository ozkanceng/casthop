# Üçüncü taraf bileşenler / Third-party components

CastHop'un kendi kodu [Apache-2.0](../LICENSE) lisanslıdır. Depoya üçüncü taraf kaynak kodu kopyalanmamıştır; aşağıdaki
bileşenler derleme veya çalışma zamanında paket yöneticisinden (Debian/Pardus deposu, Maven Central, Google Maven)
alınır. Bu liste RISKS R19 gereği tutulur ve bağımlılık eklendikçe güncellenir.

## Tahta alıcısı (`receiver/`)

| Bileşen | Kullanım | Lisans | Bağlantı biçimi |
|---|---|---|---|
| Qt 6 (Core, Gui, Network, Qml, Quick, Controls) | arayüz, ağ, TLS | LGPL-3.0 | dinamik (.deb: sistem kütüphanesi) |
| GStreamer 1.22 + plugins-base/good/bad, gst-libav, gstreamer1.0-qt6, gstreamer1.0-gl | video/ses çözme ve çizim | LGPL-2.1+ | dinamik / eklenti |
| FFmpeg (libavcodec, gst-libav üzerinden) | yazılım H.264/H.265/AAC çözme | LGPL-2.1+; Debian derlemesi GPL bileşenleri içerir (aşağıya bakın) | dinamik |
| OpenSSL 3 | HMAC-SHA256, sertifika üretimi, TLS (Qt arka ucu) | Apache-2.0 | dinamik |
| libqrencode | QR kodu | LGPL-2.1+ | dinamik |
| Avahi (libavahi-client) | mDNS yayını | LGPL-2.1+ | dinamik |
| Noto Sans / Noto Sans Mono | yazı tipi (paket bağımlılığı) | SIL OFL 1.1 | sistem yazı tipi |
| GoogleTest | yalnız birim testleri | BSD-3-Clause | derleme zamanı |
| nlohmann/json | yalnız birim testleri | MIT | derleme zamanı |

### Taşınabilir paket (`casthop-*.tar.gz`)

Taşınabilir paket Debian 12 paketlerinden alınmış Qt, GStreamer ve FFmpeg kütüphanelerini **değiştirilmeden**,
paylaşımlı kütüphane olarak içerir (`lib/`, `plugins/`, `qml/`, `gstreamer-1.0/`); kullanıcı bunları değiştirebilir
(LGPL). Debian'ın FFmpeg derlemesi GPL bileşenleri (ör. x264, x265) içerdiğinden **taşınabilir paket bütün olarak
GPL-3.0 koşullarıyla dağıtılır**; CastHop'un kendi kodu Apache-2.0'dır ve GPL-3.0 ile uyumludur. `.deb` paketi bu
kütüphaneleri içermez (sistemin depodan kurduğu paketleri kullanır).

Karşılık gelen kaynak kodu: paketteki `share/bundled-packages.txt` her gömülü Debian paketinin adını ve tam sürümünü
listeler; kaynakları `apt-get source <paket>=<sürüm>` veya <https://snapshot.debian.org/> üzerinden alınabilir. CastHop
projesi istek üzerine bu kaynakları da sağlar (GitHub Issues).

### Windows kurucusu (`casthop-setup-x86_64.exe`)

Linux'tan farklı olarak bağımlılıklar kurucunun içinde dağıtılır. Lisans metinleri `licenses\` klasörüne kopyalanır.
x264 gibi GPL eklentiler pakete alınmaz: `receiver/ci/windows/package.ps1` yalnız adı yazılı eklentileri kopyalar.

| Bileşen | Kaynak | Kullanım | Lisans | Bağlantı biçimi |
|---|---|---|---|---|
| Qt 6.8 (Core, Gui, Network, Qml, Quick, Controls) | Qt çevrim içi deposu | arayüz, ağ, TLS | LGPL-3.0 | DLL |
| GStreamer 1.26 MSVC ikilileri | gstreamer.freedesktop.org | video/ses çözme | LGPL-2.1+ | DLL / eklenti |
| FFmpeg (`gstlibav` içinde) | GStreamer ikilileri | yazılım H.264/H.265/AAC çözme | LGPL-2.1+ | DLL |
| OpenSSL 3 | vcpkg | HMAC-SHA256, sertifika, TLS | Apache-2.0 | DLL |
| libqrencode | vcpkg | QR kodu | LGPL-2.1+ | DLL |
| Microsoft Visual C++ çalışma zamanı | Visual Studio | C++ çalışma zamanı | Microsoft yazılım lisansı (dağıtılabilir) | DLL |

Kurucu [Inno Setup](https://jrsoftware.org/isinfo.php) ile üretilir; yalnız derleme aracıdır, lisansı kurucunun
dağıtımına izin verir. GoogleTest ve nlohmann-json Windows'ta da yalnız testlerde kullanılır (vcpkg).

## Tablet uygulaması (`android/`)

| Bileşen | Kullanım | Lisans |
|---|---|---|
| Kotlin standart kitaplığı, kotlinx.coroutines | dil, eşzamanlılık | Apache-2.0 |
| AndroidX (core, activity, lifecycle), Jetpack Compose, Material 3, Material Icons | arayüz | Apache-2.0 |
| CameraX (camera2, lifecycle, view) | QR tarama kamerası | Apache-2.0 |
| zxing-cpp (`io.github.zxing-cpp:android`) | QR çözme | Apache-2.0 |
| kotlinx.serialization (json) | yalnız testler ve `tools/fake-sender` | Apache-2.0 |
| JUnit 5 | yalnız testler | EPL-2.0 |

## Araçlar ve CI

`tools/integration` ve `tools/soak` test videolarını çalışma anında GStreamer'ın `x264enc`/`x265enc`/`opusenc`/
`avenc_aac` öğeleriyle üretir (gstreamer1.0-plugins-ugly/bad, GPL/LGPL); bu öğeler yalnız CI ortamında kullanılır,
dağıtılan paketlere girmez.
