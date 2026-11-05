# CastHop

**Tablet ve telefon ekranını akıllı tahtaya, televizyona ve bilgisayara kablosuz yansıtın.**
Kurulumu kolay, açık kaynak, düşük gecikmeli, şifreli.

[English](README.en.md) · [Kurulum](#kurulum) · [Kullanım](#kullanım) · [Sorun giderme](#sorun-giderme) ·
[Mimari](docs/ARCHITECTURE.md) · [Protokol](docs/PROTOCOL.md) · [Değişiklikler](CHANGELOG.md)

| Gönderen ↓ / Alan → | Pardus tahta | Android TV, Google TV, Fire TV | Apple TV | Windows | Mac | iPad, Android tablet |
|---|---|---|---|---|---|---|
| Android tablet ve telefon | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| iPhone ve iPad | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

Her iki uçta da CastHop gerekir. AirPlay, Miracast ve Chromecast desteklenmez.

## Ne işe yarar?

Öğretmen tabletindeki her şeyi — ders uygulamaları, videolar, fotoğraflar, el yazısı notlar — sesiyle birlikte
sınıftaki akıllı tahtada gösterir. Kablo, uygulama hesabı ya da internet gerekmez; tablet ve tahta aynı ağda olsun
yeter.

- **1080p, 60 kare/sn**, ağ zayıflarsa görüntü kalitesi kendiliğinden ayarlanır, gecikme birikmez.
- **Ses**: video ve uygulama sesleri tahtanın hoparlöründen, görüntüyle senkron.
- **Güvenli**: TLS 1.3 şifreleme; tahta yalnız ekranındaki QR kodunu/kodu bilen tablete açılır. Başka bir tablet
  araya girmek isterse tahtada **onay** istenir.
- **Kolay**: ilk seferde QR okutun, sonra tahtanın adına tek dokunuş. Wi-Fi kısa süre koparsa yansıtma kendiliğinden
  sürer; tablet döndürülünce görüntü de döner.
- **Kamera**: ekran yerine kamerayı gönderir; kitap, defter ve belge göstermek için tek düğme.

## Kurulum

### Tahta (Pardus ETAP 23 / Debian 12)

**En kolayı:** tahtada ve tablette [ozkanceng.github.io/casthop](https://ozkanceng.github.io/casthop/) adresini açıp adımları izleyin. Tahtada indirilen `.deb` dosyasına çift tıklayıp yönetici şifresiyle kurarsınız.

Yönetici şifreniz yoksa yalnız kendi hesabınıza kurmak için:

```bash
wget -nv --tries=10 --retry-connrefused --waitretry=5 -O- https://ozkanceng.github.io/casthop/kur | sh
```

Dosyaları elle indirmek isterseniz [Sürümler](https://github.com/ozkanceng/casthop/releases) sayfasından iki seçenekten birini kullanın:

**A. Paket (önerilen, yönetici şifresi gerekir)**

```bash
sudo apt install ./casthop_*_amd64.deb
```

Menüde **CastHop** görünür. Bağımlılıklar (Qt, GStreamer) Pardus deposundan otomatik kurulur.

**B. Taşınabilir (yönetici şifresi gerekmez)**

```bash
tar xf casthop-*-x86_64.tar.gz
cd casthop-*-x86_64
./install-user.sh
```

Uygulama `~/.local/opt/yansit` altına kurulur ve menüye eklenir.

> İsterseniz tahtada **Ayarlar → "Tahta açılınca CastHop'u başlat"** seçeneğini açın: tahta her açılışta yansıtmaya
> hazır bekler.

### Tablet (Android 10 ve üstü)

[Sürümler](https://github.com/ozkanceng/casthop/releases) sayfasından `casthop-*.apk` dosyasını tablete indirip açın.
İlk kurulumda Android "bilinmeyen kaynaklardan yüklemeye" izin isteyebilir.

### Televizyon, Windows ve Mac

| Cihaz | Dosya | Not |
|---|---|---|
| Android TV, Google TV, Fire TV, Android tablet | `casthop-tv.apk` | TV'de dosya yöneticisiyle veya `adb install` ile kurulur. |
| Windows 10 ve 11 (64 bit) | `casthop-setup-x86_64.exe` | İmzalı değilse SmartScreen uyarır: "Ek bilgi" → "Yine de çalıştır". |
| Mac (macOS 14 ve üstü) | `casthop-mac.dmg` | Apple onayı olmadan ilk açılışta uyarır: Sistem Ayarları → Gizlilik ve Güvenlik → "Yine de Aç". |

Hepsi [Sürümler](https://github.com/ozkanceng/casthop/releases) sayfasındadır.

### iPhone, iPad ve Apple TV

App Store'a eklenince kurulabilecek. Geliştirici olarak derlemek için [`apple/`](apple/) klasöründe `xcodegen` çalıştırıp
`CastHop.xcodeproj`'u açın.

## Kullanım

1. Tahtada **CastHop**'u açın. Ekranda büyük bir QR kod ve 6 haneli kod görünür.
2. Tablette **CastHop**'u açıp **Tahtaya Bağlan**'a dokunun; kamera açılınca tahtadaki QR kodunu okutun.
3. Android'in "Ekranınız paylaşılsın mı?" sorusuna **Başlat** deyin. Tablet ekranı tahtada.

Durdurmak için tablette **Yansıtmayı Durdur**'a, bildirimdeki **Durdur**'a ya da tahtaya dokunup
**Yansıtmayı durdur**'a basın. **Duraklat** ile tablette başka bir şeye geçerken tahtada son görüntü kalır.

Bir kez bağlandığınız tahta tablette **Hatırlanan tahtalar** altında listelenir; sonraki derslerde adına dokunmanız
yeter. Kamera kullanmak istemezseniz **Kodla bağlan** ile aynı ağdaki tahtayı seçip ekrandaki 6 haneli kodu girin.

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| "Tahtaya ulaşılamadı" | Tablet ve tahta aynı Wi-Fi ağında mı? Birçok okul ağı cihazların birbirini görmesini engeller (*istemci yalıtımı*). En kolay çözüm: tabletin **Mobil erişim noktası**'nı (hotspot) açın, tahtayı bu ağa bağlayın ve tahtadaki yeni QR'ı okutun. |
| "Tahtada CastHop açık değil" | Tahtada CastHop'u açın. Açıksa ve hâlâ oluyorsa tahtanın güvenlik duvarında TCP 47800 portuna izin verin. |
| "QR kodu artık geçerli değil" | Her QR kodu bir kez kullanılır; tahtadaki güncel kodu okutun. |
| "Tablet ve tahta farklı ağlarda" | Tableti tahtanın bağlı olduğu Wi-Fi'a bağlayın veya hotspot yöntemini kullanın. |
| Görüntü takılıyor / bulanık | Tablette **Ayarlar → Görüntü kalitesi → Dengeli** veya **Zayıf ağ**. Mümkünse 5 GHz Wi-Fi kullanın. |
| Ses gelmiyor | Tablette ses izni verildi mi ve **Ayarlar → Sesi tahtaya aktar** açık mı? Bazı uygulamalar (ör. bazı video uygulamaları, telefon görüşmeleri) sesini paylaşmaya izin vermez. |
| Ekran kilitlenince yansıtma duruyor | Android 15 ve üstünde bu bir güvenlik kuralıdır; ekran zaman aşımını uzatın. |

Daha fazla yardım ve hata bildirimi için: [Issues](https://github.com/ozkanceng/casthop/issues). Hata bildirirken
tahtada `casthop --verbose` çıktısını ve tablet modelini ekleyin.

## Ölçülen performans

Otomatik testler (her değişiklikte, CI) ve uzun süre testi (her gece 30 dk) sonuçları; gerçek cihaz ölçümleri
[docs/TESTING.md](docs/TESTING.md) kontrol listeleriyle toplanır.

| Ölçüm | Sonuç | Hedef |
|---|---|---|
| İlk kare (bağlantıdan sonra) | 50–90 ms (yazılım çözücü, CI) | ≤ 1 sn |
| Yakalama→çizim, aynı makinede | ≈ 1–5 ms (CI) | — |
| Wi-Fi kopmasından sonra devam | ≈ 20 ms (ağ geri gelince, CI) | ≤ 3 sn |
| Döndürme | kare kaybısız yeniden yapılandırma (CI) | < 1 sn |
| Dar bant (2,5 Mbps) | gecikme < 800 ms, birikmez (CI) | birikmemeli |
| Ses–görüntü sapması | ≈ 22 ms (CI) | ≤ ±60 ms |
| 30 dk uzun süre (görüntü + ses) | gecikme eğimi 0,04 ms/dk, bellek artışı %0,0, alıcıda kayıp 0, geç ses %0,05 ([grafik](docs/soak-30min.svg)) | eğim ≈ 0, RSS < %5 |
| Uçtan uca (tablet → tahta, 5 GHz, 1080p60) | gerçek cihazda ölçülecek ([nasıl](tools/latency/README.md)) | ≤ 80 ms (kabul ≤ 120 ms) |

## Bileşenler

| Dizin | İçerik |
|---|---|
| [`android/`](android/) | Gönderici (`app`) ve TV ile tablet alıcısı (`tv`, `receiver-core`): Kotlin, Jetpack Compose, Android 10+ |
| [`apple/`](apple/) | iPhone ve iPad göndericisi; Mac, Apple TV ve iPad alıcısı: Swift, iOS 17, tvOS 17, macOS 14 |
| [`receiver/`](receiver/) | Tahta ve Windows alıcısı (C++20, Qt 6, GStreamer), Pardus ETAP 23 / Debian 12, Windows 10+ |
| [`protocol/`](protocol/) | Protokol test vektörleri ve bağımsız referans üreteci |
| [`tools/`](tools/) | Sahte gönderici ve alıcı, alıcı uyum testi, uçtan uca testler, ağ benzetimi, gecikme ölçümü, uzun süre testi |
| [`web-receiver/`](web-receiver/) | (Sonraki aşama) kurulumsuz tarayıcı alıcısı |
| [`docs/`](docs/) | Plan, mimari, protokol, riskler, gerçek cihaz test listeleri |

## Geliştirme

Bkz. [CONTRIBUTING.md](CONTRIBUTING.md); sürüm yayımlama ve APK imzalama: [docs/RELEASING.md](docs/RELEASING.md).

## Lisans

[Apache-2.0](LICENSE). Üçüncü taraf bileşenler: [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
