# Değişiklik Günlüğü / Changelog

Biçim [Keep a Changelog](https://keepachangelog.com/tr-TR/1.1.0/) esaslıdır; sürümleme [SemVer](https://semver.org/lang/tr/) izler.

## [Yayımlanmamış / Unreleased]

### Eklendi / Added
- Kurulum sayfası (GitHub Pages) ve `site/kur`: tahtaya yönetici şifresi olmadan tek komutla kurulum, tablet için QR ve doğrudan indirme.
- Sürümlere sürümden bağımsız adlı dosyalar eklendi (`releases/latest/download/`).

## [0.1.1] - 2026-11-04

### Eklendi / Added
- Tablet uygulamasına "Nasıl bağlanırım?" rehberi: QR ve kodla bağlanma, hatırlanan tahtalar, hotspot yolu, yansıtma sırasında yapılabilecekler ve sık sorunlar; ana ekranda 3 adımlık kısa özet.
- Tablet uygulaması yeniden tasarlandı: tam renk token'ları (açık/koyu), ikonlar, kartlar, yatay tablette iki sütun, istatistik kutuları ve QR tarayıcı çerçevesi.

## [0.1.0] - 2026-11-04

### Eklendi / Added
- Proje planı, mimari, protokol v1 taslağı ve risk analizi (`docs/`).
- Monorepo iskeleti: `android/` (Kotlin + Compose kabuğu, saf-JVM `:protocol` modülü), `receiver/` (C++20 + Qt6 QML kabuğu; tahtanın paylaşılabilir ağ adreslerini listeler).
- CI: Android lint/test/APK, alıcı Debian 12 derleme + test (normal ve ASan/UBSan), `.deb` paketi, gizli bilgi taraması; etiketle otomatik sürüm yayımlama.
- `docs/RELEASING.md`: APK imzalama (GitHub Secrets) ve sürüm yayımlama rehberi.
- Protokol v1 donduruldu: bağımsız Python referans üreteci (`protocol/gen/`) ve ortak test vektörleri (`protocol/vectors/vectors.json`: çerçeve, tüm CONTROL mesajları, HMAC kanıtları, QR URI).
- Kotlin protokol kütüphanesi (`android/protocol`): çerçeve/TLV kodlama, akış okuyucu/yazıcı, kimlik kanıtları, QR ayrıştırma, saat senkronu, AIMD bit hızı denetleyicisi; vektör, fuzz ve senaryo testleri.
- C++ protokol kütüphanesi (`receiver/src/protocol`): aynı kodlama ve hata kodları, artımlı çerçeve ayrıştırıcı, OpenSSL ile HMAC; `PairingManager` (tek kullanımlık QR token, PIN kilitleme, hız sınırı, eşleştirme anahtarları).
- CI: vektörlerin üreteçle uyumunu denetleyen `protocol.yml`.
- Alıcı MVP (M2): TLS 1.3 sunucu (kendinden imzalı ECDSA P-256 kimlik, ALPN `yansit/1`), oturum durum makinesi (tek gönderici, tahtada devralma onayı, ATTACH, RESUME penceresi), GStreamer video boru hattı (VA donanım çözücü → yazılım yedeği, birikme denetimi, anahtar kare isteği), alıcı raporları ve gecikme ölçümü.
- Alıcı arayüzü: büyük QR + 6 haneli kod bekleme ekranı, tam ekran yansıtma, dokununca bilgi çubuğu ve "Yansıtmayı durdur", devralma onay diyaloğu; Türkçe/İngilizce.
- Gönderici istemci kütüphanesi (`android/protocol` → `client`): parmak izi sabitlemeli TLS, HELLO/ATTACH/RESUME el sıkışmaları.
- `tools/fake-sender` ve `tools/integration`: CI'da uçtan uca test (yanlış token, 600 kare akış, ikinci gönderici onayı, tek kullanımlık token, Xvfb + Mesa ile GL arayüz yolu).
- `docs/TESTING.md`: tahtada M2 kontrol listesi.
- Android gönderici MVP (M3): izin ekranı (kamera, yerel ağ [Android 17], bildirim), QR tarama (CameraX + zxing-cpp), tahtaya bağlanma (paralel adres denemesi, parmak izi denetimi, tahta onayı), sistem ekran paylaşımı onayı, `MirrorService` ön plan servisi ve bildirimden durdurma, MediaCodec H.264 kodlayıcı (kalıcı giriş Surface'ı, düşük gecikme anahtarları, CBR, IDR isteği), ön ayarlar ve yalnız desteklenen değerleri gösteren gelişmiş ayarlar, anlaşılır hata mesajları; Türkçe/İngilizce.
- Dayanıklılık (M4): Wi-Fi kopmasında RESUME ile ≤ 3 sn'de devam, uzun kopmada eşleştirmeyle otomatik yeniden bağlanma; döndürmede VirtualDisplay yeniden boyutlandırma + kodlayıcı yeniden yapılandırma; dar bantta gecikme bütçesi (yol boşaltma) ve bit hızına göre gönderim tamponu; kodlayıcı hatasında yeniden başlatma ve bir alt ön ayara düşme; duraklat/devam et.
- Paketleme (M8): taşınabilir alıcı paketi (`tar.gz`, Qt + GStreamer gömülü, yönetici yetkisiz `install-user.sh`); CI'da temiz Debian 12'de denenir; kurulu `.deb` ve taşınabilir paket her değişiklikte çalıştırılarak test edilir; yayın uçtan uca testlere bağlı.

### Düzeltildi / Fixed
- Kurulu alıcıda (derleme dizini dışında) QML `Theme` tekili bulunamıyor ve arayüz açılışta takılıyordu: Qt 6.4'te `qrc:/` içe aktarma yolu eklendi (kurulum testleriyle yakalandı). `TouchButton.qml` eksik `import Yansit` eklendi.

### Eklendi / Added
- Ölçüm (M7): gecikme test deseni (tablette Ayarlar → Gecikme ölçümü; 64 hücrelik zaman damgası bandı + büyük saat); tahta deseni çözülen karede okuyup karenin ekrana verildiği anda uçtan uca gecikmeyi (ortalama, p95) bilgi çubuğunda ve istatistiklerde gösterir. `tools/soak`: 30 dk uzun süre testi (dakikalık ortanca gecikme eğimi, RSS, kayıp, geç ses) → CSV + SVG; CI'da her gece.
- Ses zamanlaması: hedef gecikme artık sesin kendi varış gecikmesini (p95) de hesaba katar; ses boru hattına tampon kuyruğu eklendi (geç paketler ve GStreamer "add queues" uyarısı giderildi).
- Cila (M6): tahtada kalıcı ayarlar paneli (tahta adı, ses, sığdır/doldur, koyu/açık tema, açılışta başlat, port, eşleştirilmiş tabletleri unutma), ekran görüntüsü (`~/Resimler/Yansıt`), pencere boyutuna göre ölçeklenen arayüz, Avahi ile mDNS yayını (`_yansit._tcp`); tablette ağdaki tahtayı bulup 6 haneli kodla bağlanma (Android 17'de izinsiz sistem seçicisi, öncesinde liste; mDNS parmak izi öneki doğrulanır), H.265 seçeneği (yalnız tahta donanımla çözebiliyorsa), bağlantı kalitesi etiketi.
- Ses (M5): tabletin çaldığı ses (AudioPlaybackCapture: medya, oyun) Opus'a (yoksa AAC-LC) kodlanıp ayrı AUDIO bağlantısından gönderilir; tahtada `opusdec`/`avdec_aac` ile, göndericinin yakalama anına + görüntünün çizim gecikmesine hizalanarak çalınır (dudak senkronu). Duraklatmada ses de durur; ses hatası görüntüyü etkilemez. Ayarlarda aç/kapa, izin ekranında isteğe bağlı ses izni.
- Hatırlanan tahtalar: ilk QR bağlantısında kalıcı eşleştirme; sonraki bağlantılar tek dokunuşla, QR'sız. Tahta eşleştirmeyi unutursa anlaşılır mesaj.
- Ağ teşhisi: bağlantı reddi / farklı alt ağ / zaman aşımı ayrımı ve tabletin hotspot'u ile bağlanma rehberi.
- `tools/integration/netsim.py`: ayrıcalıksız ağ benzetim vekili (bant sınırı, gecikme, kopma); CI'da döndürme, kopma+RESUME, dar bant ve kalıcı eşleştirme senaryoları.
- `android/sender`: saf JVM gönderici çekirdeği (yayın planlama, kare atmalı gönderim kuyruğu, oturum ve bit hızı uyarlaması); `fake-sender --engine mirror` ile CI'da gerçek alıcıya karşı test edilir.
