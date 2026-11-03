# Yansıt — Geliştirme Planı

> Android tablet ekranını (ve sesini) Pardus ETAP akıllı tahtalarına kablosuz, düşük gecikmeyle yansıtan açık kaynaklı sistem.
>
> Durum: **Aşama 0 — plan onayı bekleniyor.** Bu belge onaydan sonra kilometre taşlarıyla birlikte güncellenir.

## 0. Ad ve kimlikler

| Öğe | Değer |
|---|---|
| Proje adı | **Yansıt** (`yansit`) |
| Depo | `github.com/ozkanceng/yansit` (public, Apache-2.0) |
| Android paket adı | `io.github.ozkanceng.yansit` |
| Alıcı ikili / .deb | `yansit-receiver` (menüde "Yansıt Alıcı") |
| Varsayılan TCP portu | `47800` (ayarlanabilir) |
| mDNS servis tipi | `_yansit._tcp` |
| QR URI şeması | `yansit://` |

## 1. Doğrulanmış platform gerçekleri (2026-11-03)

Tahmin değil, kaynaktan kontrol edildi. Ayrıntı ve kaynaklar: [ARCHITECTURE.md §9](ARCHITECTURE.md#9-doğrulanmış-sürümler-ve-kaynaklar).

- **Android 17 (API 37)** 2026-06'da kararlı. Play zorunluluğu: 2026-08-31'den itibaren targetSdk 36. → `targetSdk = compileSdk = 37`, `minSdk = 29`.
- **Android 17 + targetSdk 37: yerel ağ erişimi varsayılan kapalı.** Gelen/giden TCP için `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES grubu, çalışma zamanı izni) şart. NsdManager picker ile çözülen adresler izin gerektirmez.
- **MediaProjection:** her oturumda kullanıcı onayı; bir token ile **tek** `createVirtualDisplay()` (Android 14+ aksi `SecurityException`). `MediaProjection.Callback` kaydı zorunlu. Döndürme/boyut değişimi `onCapturedContentResize()` ile → aynı VirtualDisplay `resize()` edilir (yeniden oluşturulamaz).
- **Android 15 QPR1+:** durum çubuğu çipi; **ekran kilitlenince yansıtma otomatik durur** (RISKS R2).
- FGS: manifest'te `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`, servis `foregroundServiceType="mediaProjection"`; onaydan sonra `startForeground()`, ardından `getMediaProjection()`.
- **Pardus ETAP 23 = Debian 12 (bookworm)** tabanlı. Bookworm paketleri: GStreamer **1.22.0**, Qt **6.4.2**, CMake 3.25.1, GCC 12.2, OpenSSL 3.0, libqrencode 4.1.1, Avahi 0.8. `gstreamer1.0-qt6` (qml6glsink) bookworm'da var. `va` eklentisi (vah264dec, rank primary+1) plugins-bad içinde; eski `gstreamer-vaapi` kullanımdan kalkıyor.
- **Açık soru:** ETAP 23'ün masaüstü ortamı ve görüntü sunucusu (X11/Wayland) resmi kaynaklarda bulunamadı → tahtada komut çıktısı istenecek (§5).

## 2. Kapsam

**MVP (v0.1.0):** Android gönderici + Pardus masaüstü alıcısı; H.264 (opsiyonel H.265) video, Opus/AAC ses, QR/PIN eşleştirme, TLS, uyarlamalı bit hızı, otomatik yeniden bağlanma, .deb + APK, CI/CD.

**MVP dışı (Sonraki aşamalar, §6):** web alıcı, tahtadan dokunmatik kontrol, üzerine çizim, kayıt, çoklu tablet geçişi.

## 3. Kilometre taşları

Her taş sonunda: derle → test → kabul kriterlerini kontrol et → commit + push. Bir taş bitmeden sonrakine geçilmez. 🔧 = gerçek cihaz testi gerekir; kontrol listesi verilip sonucun beklenir.

### M0 — Depo iskeleti ve CI tabanı
- `gh repo create ozkanceng/yansit --public`, Apache-2.0 `LICENSE`, `NOTICE`, `.gitignore`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue/PR şablonları, `CHANGELOG.md`, TR/EN README iskeleti.
- Monorepo: `/android`, `/receiver`, `/web-receiver` (yalnız README), `/protocol`, `/tools`, `/docs`.
- Android: Gradle (sürümler M0'da Google Maven'den kararlı olanlara sabitlenir, version catalog), Compose uygulama kabuğu, `:protocol` saf-JVM modülü.
- Receiver: CMake + Qt6 QML kabuğu, `debian:12` konteynerinde derlenir.
- GitHub Actions: `android.yml` (lint + unit test + assembleRelease), `receiver.yml` (debian:12'de derleme + ctest), `release.yml` (tag → artefaktlar).

**Kabul:** Boş-ama-çalışan iki uygulama CI'da yeşil; depoda gizli bilgi yok (`gitleaks` CI adımı geçer).
**Test:** CI çalıştırması; yerelde `./gradlew test`, `cmake --build && ctest`.

### M1 — Protokol v1 (iki dilde) + ortak test vektörleri
- `docs/PROTOCOL.md` tanımı → bağımsız Python üreteci (`protocol/gen/`) → `protocol/vectors/vectors.json` (ham baytlar + beklenen ayrıştırma/hata kodu).
- Kotlin (`android/protocol`, saf JVM) ve C++ (`receiver/src/protocol`, Qt'siz, yalnız STL) kodlayıcı/çözücü.
- Token/PIN/eşleştirme doğrulama (HMAC-SHA256, sabit-zamanlı karşılaştırma, `PairingManager`), saat senkronu (PING/PONG), bit hızı denetleyicisi (Kotlin, saf mantık). Bağlantı başına el sıkışma durum makinesi TLS sunucusuyla birlikte M2'de.
- Fuzz-benzeri testler: rastgele/bozuk çerçeveler çökmeye yol açmamalı.

**Kabul:** Her iki uygulama aynı vektörlerin %100'ünü geçer; bozuk girdide istisna/çökme yok, tanımlı hata kodu döner; bit hızı denetleyicisi senaryo testleri geçer.
**Test:** `./gradlew :protocol:test`, `ctest -R protocol`.

### M2 — Alıcı MVP (video) + sahte gönderici + CI entegrasyon testi
- Qt6/QML tam ekran uygulama: bekleme ekranı (büyük QR + 6 haneli PIN + IP listesi), TLS sunucu, el sıkışma, tek-gönderici kuralı.
- GStreamer: `appsrc → h264parse → (vah264dec | avdec_h264) → glupload → qml6glsink`; donanım çözücü yoksa otomatik yazılıma düşer.
- Bağlanınca tam ekran, kopunca bekleme ekranı.
- `tools/fake-sender` (Kotlin/JVM, `:protocol` modülünü kullanır): Annex-B H.264 dosyasını gerçek zaman hızında yollar → Kotlin↔C++ çapraz uyum testi.
- `--headless` modu (QT_QPA_PLATFORM=offscreen, `fakesink`) ile CI entegrasyon testi.

**Kabul:** CI'da sahte gönderici → alıcı: ilk kare ≤ 1 sn, 600 kare çözülür, kare kaybı 0 (yerel döngüde), token'sız bağlantı reddedilir, ikinci gönderici reddedilir/onay bekler.
**Test:** `tools/integration/run.sh` (CI'da). 🔧 Pardus tahtada `vainfo` + manuel akış testi (fake-sender ile).

### M3 — Android gönderici MVP (video) 🔧
- İlk açılış: tek seferlik izin ekranı (kamera, bildirim, yerel ağ [API 37], mikrofon [ses için]).
- Ana ekran: tek büyük **"Tahtaya Bağlan"** → QR tarama (CameraX + zxing-cpp) → sistem yansıtma onayı → yayın. (≤ 3 adım)
- `MediaProjection` + FGS + `VirtualDisplay` → `MediaCodec` kalıcı giriş Surface'ı; codec yetenekleri çalışma anında sorgulanır; ön ayarlar (En iyi / Dengeli / Zayıf ağ) + gelişmiş ayarlar.
- Bildirimden ve uygulamadan tek dokunuşla durdurma.
- `docs/TESTING.md` ilk sürümü.

**Kabul:** 5 GHz ağda 1080p60 görüntü tahtada; durdurma ≤ 1 sn; desteklenmeyen ayar menüde görünmez.
**Test:** birim (codec seçim mantığı, ön ayar hesapları — Robolectric'siz saf JVM), 🔧 TESTING.md M3 listesi.

### M4 — Dayanıklılık ve canlılık 🔧
- Döndürme / uygulama-paylaşımı boyut değişimi: `VirtualDisplay.resize()` + kodlayıcı yeniden yapılandırma, kare kaybı < 1 sn.
- Yeni bağlantıda anında IDR (+SPS/PPS önde).
- Gönderici tıkanıklık yönetimi: sınırlı gönderim kuyruğu, birikme → bekleyen kareleri at + IDR iste + bit hızını düşür.
- Alıcı geri bildirimi (RECEIVER_REPORT) → AIMD bit hızı uyarlaması.
- Wi-Fi kopmasında oturum devamı (resume token) ≤ 3 sn.
- Duraklat ("Ders duraklatıldı" ekranı), kalıcı eşleştirme (hatırlanan tahtalar), devralma onayı (tahtada dokunmatik diyalog).
- Ağ teşhisi: bağlanamazsa nedeni sınıflandır (port kapalı / zaman aşımı / farklı alt ağ) → istemci izolasyonu + hotspot rehberi.
- Kodlayıcı/çözücü hatası: çökmeden yeniden başlatma, anlaşılır mesaj.

**Kabul:** Kalite hedeflerindeki döndürme (< 1 sn), toparlanma (≤ 3 sn) maddeleri; CI'da `tc netem` ile gecikme/kayıp enjekte edilen entegrasyon testinde gecikme birikmez.
**Test:** birim + CI netem testi + 🔧 TESTING.md M4 listesi.

### M5 — Ses 🔧
- `AudioPlaybackCapture` (`AudioPlaybackCaptureConfiguration`, USAGE_MEDIA/GAME/UNKNOWN) → Opus (yoksa AAC-LC) `MediaCodec`.
- Alıcıda `opusdec`/`avdec_aac`, ortak saat tabanında senkron oynatma.
- Yakalanamayan uygulamalar için açıklama ekranı.

**Kabul:** Dudak senkronu sapması ≤ ±60 ms (test deseniyle ölçülür); ses kapalıyken video gecikmesi değişmez.
**Test:** birim (zaman damgası eşleme), CI'da sahte ses akışı, 🔧 TESTING.md M5.

### M6 — Alıcı ve gönderici cilası
- Alıcı ayarları: port, ses, ölçekleme (sığdır/doldur), açılışta otomatik başlat; ekran görüntüsü; büyük dokunmatik kontroller; çoklu ağ arayüzü IP'leri QR'da.
- mDNS: Avahi yayını + Android `NsdManager` (API 37'de `FLAG_SHOW_PICKER`).
- H.265 seçeneği (yalnız alıcı donanımla çözebiliyorsa sunulur).
- Bağlantı kalitesi göstergesi (gecikme, FPS, bit hızı), TR/EN, karanlık/aydınlık tema.

**Kabul:** Tüm ayarlar kalıcı; mDNS engelli ağda QR akışı etkilenmez; TR/EN dizelerinin tamamı çevrilmiş (lint "MissingTranslation" 0).

### M7 — Ölçüm araçları ve uzun süre testi 🔧
- `tools/latency`: Android test deseni aktivitesi (her karede ikili kodlanmış zaman damgası) + alıcı ölçüm modu (çözülen karedeki damgayı okur, senkronize saatle farkı hesaplar) + manuel "iki ekranı tek fotoğrafta çek" yöntemi.
- `tools/soak`: 30 dk akış; RSS bellek, gecikme, kare kaybı CSV + grafik.

**Kabul:** 1080p60 / 5 GHz: uçtan uca ≤ 80 ms hedef, ≤ 120 ms kabul; 30 dk'da gecikme eğimi ≈ 0, RSS artışı < %5; sonuçlar README'de.

### M8 — Paketleme ve yayın
- `receiver/debian/` (debhelper, `${shlibs:Depends}` + GStreamer eklenti bağımlılıkları), masaüstü kısayolu, ikon, otomatik başlatma seçeneği.
- Taşınabilir sürüm: AppImage (Qt + GStreamer gömülü, VA sürücüleri sistemden).
- APK imzalama (GitHub Secrets), tag → GitHub Releases (APK + .deb + AppImage + SHA256SUMS).
- README TR/EN tam (ekran görüntüleri/GIF, öğretmen için adım adım kurulum, sorun giderme).
- `v0.1.0` etiketi.

**Kabul:** Temiz Debian 12 / Pardus 23 kurulumunda `apt install ./yansit-receiver_*.deb` tek komutla çalışır; tag push'u insan müdahalesiz Release üretir.

## 4. Test stratejisi (özet)

| Katman | Ne | Nerede |
|---|---|---|
| Birim | çerçeve kodlama/çözme, el sıkışma, token/PIN, saat senkronu, bit hızı denetleyicisi, codec seçimi | CI (JVM + ctest) |
| Çapraz uyum | Kotlin ↔ C++ aynı test vektörleri | CI |
| Entegrasyon | fake-sender (Kotlin) → headless receiver; netem ile bozulmuş ağ | CI (debian:12 konteyner) |
| Gerçek cihaz | `docs/TESTING.md` kontrol listeleri | Kullanıcı |
| Performans | gecikme ölçüm aracı, 30 dk soak | Kullanıcı + CI (kısa soak) |

## 5. Senden istenenler (plan onayıyla birlikte)

1. Plan onayı veya değişiklik istekleri.
2. Bir ETAP 23 tahtasında şu çıktı (X11/Wayland, donanım ve sürücü kararını netleştirir):
   ```bash
   echo "$XDG_SESSION_TYPE $XDG_CURRENT_DESKTOP"; cat /etc/os-release; lscpu | grep 'Model name'; vainfo 2>&1 | head -40; gst-inspect-1.0 --version; dpkg -l | grep -E 'gstreamer1.0-(qt6|plugins-bad|libav|vaapi)|libqt6core6' ; xrandr --current 2>/dev/null | head -3
   ```
3. Test tableti/tabletleri: model + Android sürümü.
4. Öğretmen tahtada `sudo`/paket kurma yetkisine sahip mi, yoksa kurulum merkezi yönetimle mi yapılıyor?

## 6. Sonraki aşamalar (MVP sonrası)

| # | Özellik | Ana teknik |
|---|---|---|
| S1 | Kurulumsuz tarayıcı alıcısı | Tablette HTTP + WebSocket sunucu; tahtada tarayıcıda WebCodecs `VideoDecoder`; aynı çerçeve formatı WS mesajı içinde |
| S2 | Tahtadan tableti dokunmatik kontrol | `AccessibilityService.dispatchGesture`, açık kullanıcı izni, protokole INPUT mesajları (v2) |
| S3 | Görüntü üzerine kalemle çizim | Alıcıda QML katmanı (Canvas/Shape), görüntüyle birlikte ekran görüntüsü |
| S4 | Kayıt ve çoklu tablet sırayla geçiş | Alıcıda `tee → mp4mux` (yeniden kodlamasız), çoklu oturum kuyruğu + öğretmen seçimi |
| S5 | UDP/RTP taşıma seçeneği | TCP hedefi tutmazsa; RTP + FEC, aynı kontrol kanalı |
| S6 | PAKE ile PIN eşleştirme | CPace/SPAKE2 — PIN modunu aktif MITM'e karşı güçlendirir |
| S7 | Pardus depolarına/Yazılım Merkezi'ne giriş | Paket bakımcısıyla iletişim |
