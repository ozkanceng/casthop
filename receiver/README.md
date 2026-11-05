# receiver/ — CastHop alıcı / receiver

C++20 + Qt 6.4 (QML) + GStreamer 1.22. Hedef: Pardus ETAP 23 / Debian 12, x86_64. Windows 10 1809+ x86_64 de
desteklenir; bkz. [Windows](#windows).

```bash
ci/install-deps.sh                 # Debian 12 / Pardus 23 (sudo)
ci/build-and-test.sh build         # derle + test
./build/src/casthop --windowed
ci/build-deb.sh out                # .deb paketi
```

Debian dışında (ör. macOS) CI ile aynı ortam:

```bash
docker build -t yansit-deb12 -f receiver/ci/Dockerfile receiver/ci
docker run --rm -v "$PWD":/src -w /src yansit-deb12 receiver/ci/build-and-test.sh /tmp/build
```

Derleme seçenekleri: `-DYANSIT_SANITIZE=ON` (ASan + UBSan), `-DYANSIT_BUILD_TESTS=OFF`.

## Windows

Windows derlemesi ayrı bir daldır: Linux sürüm sınırları (Qt 6.4.2, GStreamer 1.22) ortak kodu bağlar, Windows'ta
Qt 6.8 ve GStreamer 1.26 kullanılır. Gerekenler: Visual Studio 2022 (C++ x64), Qt 6.8 `msvc2022_64`, vcpkg ve Inno
Setup 6.3+. Bağımlılıklar `vcpkg.json` ve `ci/windows/install-deps.ps1` içindedir.

```powershell
receiver\ci\windows\install-deps.ps1     # GStreamer MSVC çalışma zamanı + geliştirme paketi (yönetici)
$env:QT_ROOT_DIR = "C:\Qt\6.8.3\msvc2022_64"; $env:VCPKG_ROOT = "C:\vcpkg"
receiver\ci\windows\build.ps1            # derle + birim testleri
receiver\ci\windows\package.ps1          # out\casthop-setup-x86_64.exe
receiver\ci\windows\test-package.ps1 -Setup out\casthop-setup-x86_64.exe -FakeSender <fake-sender.bat>
```

Kurucu `C:\Program Files\CastHop` altına GStreamer'ın kendi düzenini korur (`bin`, `lib\gstreamer-1.0`,
`libexec\gstreamer-1.0`); alıcı bu düzeni görünce sistemdeki başka bir GStreamer'ı yok sayar. Güvenlik duvarında
TCP 47800 ve mDNS (UDP 5353) açılır; Ayarlar'dan başka port seçilirse kural elle güncellenmelidir. Kaldırıcı kuralları
ve programı siler; ayarlar ve eşleştirmeler için sorar.

| | Windows'ta |
|---|---|
| Çözücü | `d3d11h264dec`, `d3d11h265dec`; yoksa `avdec_h264`. H.265 yalnız donanımla sunulur |
| Çizim | İşlemci (`CpuVideoItem`). Paket `qml6glsink` içermez; yüklenebilen bir eklenti varsa GL kullanılır |
| Ağda bulma | `DnsServiceRegister`, çalışma anında yüklenir; bulunmazsa yalnız bu kolaylık olmaz |
| Ayarlar | `HKCU\Software\Yansit\yansit-receiver` |
| Kalıcı veriler | `%APPDATA%\Yansit\yansit-receiver`: anahtar ve `trusted.json` yalnız geçerli kullanıcıya açık |
| Tanı günlüğü | `%LOCALAPPDATA%\cache\yansit\receiver.log` |
| Otomatik başlatma | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, `CastHop` değeri |

Program GUI alt sisteminde derlenir. `--headless` konsoldan başlatılınca konsola bağlanır ve Ctrl-C ile düzgün kapanır;
çıktı bir dosyaya yönlendirilirse günlük için `QT_FORCE_STDERR_LOGGING=1` gerekir.

## Çalıştırma seçenekleri

| Seçenek | Açıklama |
|---|---|
| `--windowed` | Tam ekran yerine pencerede başlat |
| `--board-name AD` | Tabletlerde görünen tahta adı (varsayılan: ayarlar, yoksa bilgisayar adı). Not: `--name` Qt'nin X11 seçeneğidir |
| `--port N` | TCP portu (varsayılan 47800) |
| `--verbose` | Ayrıntılı günlük (`yansit.*` kategorileri) |
| `--headless` | Pencere yok; video çözülür ama gösterilmez (otomatik testler) |
| `--pairing-file F` | QR URI ve PIN her değiştiğinde JSON olarak yazılır (otomasyon) |
| `--stats-file F` | Her oturum sonunda istatistik satırı (JSON Lines) |
| `--approval-timeout MS` | İkinci tablet için tahta onayı süresi (varsayılan 20000) |
| `--qr-host IP` | QR'a konacak adres (tekrarlanabilir; varsayılan: otomatik) |
| `--no-audio` | Tabletin sesini çalma (WELCOME `audio_enabled=0`) |

Ortam değişkenleri: `YANSIT_DECODER=avdec_h264` çözücüyü zorlar; `YANSIT_VA_DMABUF=1` VA → GL sıfır kopya yolunu
dener (GStreamer 1.22'de sürücüye bağlı, bkz. RISKS R6).

Ayarlar (`~/.config/Yansit/yansit-receiver.conf`) bekleme ekranındaki **Ayarlar** düğmesinden değiştirilir; komut
satırı seçenekleri o çalışma için önceliklidir. "Tahta açılınca başlat" `~/.config/autostart/` altına bir XDG
autostart dosyası yazar. Ekran görüntüleri `~/Resimler/CastHop/` altına kaydedilir.

mDNS: `avahi-daemon` çalışıyorsa tahta `_yansit._tcp` olarak yayınlanır (tabletler QR'sız bulup 6 haneli kodla
bağlanabilir). Avahi yoksa yalnız bu kolaylık olmaz; QR akışı etkilenmez. `YANSIT_ALLOW_SW_HEVC=1` H.265'i yazılım
çözücüyle de sunar (yalnız test).

Kalıcı veriler `~/.local/share/Yansit/yansit-receiver/`: `identity/` (TLS sertifikası, anahtar 0600) ve
`trusted.json` (eşleştirilmiş tabletler, 0600). Sertifika silinirse QR parmak izi değişir ve eşleştirmeler
yeniden yapılmalıdır.

## Yapı

```
src/protocol/   çerçeve, TLV, kimlik kanıtları, QR (STL + OpenSSL; Qt yok)
src/session/    PairingManager (token/PIN/eşleştirme), SessionManager (oturum durum makinesi)
src/net/        TLS kimliği, TLS 1.3 sunucu, bağlantı, mDNS (`MdnsAvahi`/`MdnsWindows`), `SecureFile*`
src/media/      GStreamer video boru hattı (donanım → yazılım çözücü yedeği, birikme denetimi)
src/app/        ReceiverService (hepsini bağlar), main, QR görüntü sağlayıcı, `Platform*` (işletim sistemi farkları)
src/qml/        bekleme ekranı, yansıtma katmanı, devralma onayı
```

Uçtan uca test: [`tools/integration/`](../tools/integration/run.sh).
