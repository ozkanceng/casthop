# receiver/ — Yansıt alıcı / receiver

C++20 + Qt 6.4 (QML) + GStreamer 1.22. Hedef: Pardus ETAP 23 / Debian 12, x86_64.

```bash
ci/install-deps.sh                 # Debian 12 / Pardus 23 (sudo)
ci/build-and-test.sh build         # derle + test
./build/src/yansit-receiver --windowed
ci/build-deb.sh out                # .deb paketi
```

Debian dışında (ör. macOS) CI ile aynı ortam:

```bash
docker build -t yansit-deb12 -f receiver/ci/Dockerfile receiver/ci
docker run --rm -v "$PWD":/src -w /src yansit-deb12 receiver/ci/build-and-test.sh /tmp/build
```

Derleme seçenekleri: `-DYANSIT_SANITIZE=ON` (ASan + UBSan), `-DYANSIT_BUILD_TESTS=OFF`.

## Çalıştırma seçenekleri

| Seçenek | Açıklama |
|---|---|
| `--windowed` | Tam ekran yerine pencerede başlat |
| `--name AD` | Tabletlerde görünen tahta adı (varsayılan: bilgisayar adı) |
| `--port N` | TCP portu (varsayılan 47800) |
| `--verbose` | Ayrıntılı günlük (`yansit.*` kategorileri) |
| `--headless` | Pencere yok; video çözülür ama gösterilmez (otomatik testler) |
| `--pairing-file F` | QR URI ve PIN her değiştiğinde JSON olarak yazılır (otomasyon) |
| `--stats-file F` | Her oturum sonunda istatistik satırı (JSON Lines) |
| `--approval-timeout MS` | İkinci tablet için tahta onayı süresi (varsayılan 20000) |
| `--qr-host IP` | QR'a konacak adres (tekrarlanabilir; varsayılan: otomatik) |

Ortam değişkenleri: `YANSIT_DECODER=avdec_h264` çözücüyü zorlar; `YANSIT_VA_DMABUF=1` VA → GL sıfır kopya yolunu
dener (GStreamer 1.22'de sürücüye bağlı, bkz. RISKS R6).

Kalıcı veriler `~/.local/share/Yansit/yansit-receiver/`: `identity/` (TLS sertifikası, anahtar 0600) ve
`trusted.json` (eşleştirilmiş tabletler, 0600). Sertifika silinirse QR parmak izi değişir ve eşleştirmeler
yeniden yapılmalıdır.

## Yapı

```
src/protocol/   çerçeve, TLV, kimlik kanıtları, QR (STL + OpenSSL; Qt yok)
src/session/    PairingManager (token/PIN/eşleştirme), SessionManager (oturum durum makinesi)
src/net/        TLS kimliği, TLS 1.3 sunucu, bağlantı (çerçeve ayrıştırma, zaman aşımları)
src/media/      GStreamer video boru hattı (donanım → yazılım çözücü yedeği, birikme denetimi)
src/app/        ReceiverService (hepsini bağlar), main, QR görüntü sağlayıcı
src/qml/        bekleme ekranı, yansıtma katmanı, devralma onayı
```

Uçtan uca test: [`tools/integration/`](../tools/integration/run.sh).
