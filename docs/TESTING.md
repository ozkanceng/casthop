# Yansıt — Gerçek Cihaz Test Kontrol Listeleri

CI her değişiklikte birim testlerini ve sahte gönderici ↔ alıcı uçtan uca testini (`tools/integration/run.sh`)
çalıştırır. Bu belge CI'ın yapamadıklarını, yani **gerçek tahta ve tabletle** yapılacak denetimleri listeler.

Her listenin sonunda "Sonuç bildirimi" bölümü var: çıktıları bir GitHub issue'suna yapıştırmanız yeterli.

---

## M2 — Tahtada alıcı + sahte gönderici

Amaç: Alıcının Pardus ETAP tahtada açıldığını, donanım video çözücüyü bulduğunu ve görüntüyü tam ekran,
akıcı gösterdiğini doğrulamak. Henüz tablet uygulaması yok; görüntüyü bir bilgisayardaki **sahte gönderici**
yollar.

### Hazırlık

1. [Actions](https://github.com/ozkanceng/yansit/actions) → son başarılı **Receiver** çalışması → `receiver-deb`
   artefaktını indirin. **Integration** çalışmasından `fake-sender` artefaktını indirin.
2. Tahtada kurun:
   ```bash
   sudo apt install ./yansit-receiver_*.deb
   ```
3. Tahta bilgilerini toplayın (sonuç bildirimine eklenecek):
   ```bash
   cat /etc/os-release | head -3
   echo "$XDG_SESSION_TYPE"
   lscpu | grep 'Model name'
   vainfo 2>&1 | head -20
   gst-inspect-1.0 vah264dec qml6glsink 2>&1 | grep -E 'Factory Details|Long-name|No such'
   ```
   `vainfo` yoksa: `sudo apt install vainfo`.

### Sahte göndericiyi hazırlama (aynı ağdaki bir bilgisayar, Java 17+)

```bash
unzip fake-sender.zip -d fake-sender
chmod +x fake-sender/bin/fake-sender
# 1080p60 test videosu (GStreamer veya ffmpeg ile):
gst-launch-1.0 videotestsrc num-buffers=1800 pattern=ball \
  ! video/x-raw,width=1920,height=1080,framerate=60/1 \
  ! x264enc tune=zerolatency speed-preset=veryfast key-int-max=120 bframes=0 bitrate=12000 \
  ! video/x-h264,stream-format=byte-stream,profile=high ! filesink location=test.h264
# veya: ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=60 -t 30 -c:v libx264 -tune zerolatency \
#        -bf 0 -g 120 -b:v 12M -bsf:v h264_mp4toannexb test.h264
```

QR içeriği: tahtadaki QR'ı telefonun kamerasıyla okutup metni (`yansit://c?...`) kopyalayın. Alternatif olarak
alıcıyı `yansit-receiver --pairing-file ~/yansit-pairing.json` ile başlatıp dosyadaki `uri` değerini alın.

### Denetimler

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Menüden **Yansıt Alıcı**'yı açın | Tam ekran bekleme ekranı: büyük QR, 6 haneli kod, tahta adı ve IP adresi | ☐ |
| 2 | Ağ kablosunu/Wi-Fi'ı kesip 10 sn bekleyin, geri bağlayın | Önce "Ağ bağlantısı yok" uyarısı, bağlanınca QR geri gelir | ☐ |
| 3 | `fake-sender/bin/fake-sender --uri 'yansit://c?...' --input test.h264 --fps 60 --frames 1800` | 1 sn içinde görüntü tam ekran; top akıcı hareket eder, takılma yok | ☐ |
| 4 | Akış sırasında ekrana dokunun | Üstte gönderici adı ve `… ms · 60 fps · … Mbps` bilgisi, 4 sn sonra kaybolur | ☐ |
| 5 | Akış bitince | Bekleme ekranına dönülür, QR **yenilenmiş** olur (eski QR artık geçmez) | ☐ |
| 6 | Yeni QR ile yeniden başlatın, akarken dokunup **Yansıtmayı durdur** | 1 sn içinde bekleme ekranı; göndericide `bye_reason` 1 | ☐ |
| 7 | Akış sürerken (önce bekleme ekranındaki 6 haneli kodu not edin) ikinci terminalde: `fake-sender --uri '…' --auth pin --pin 123456 --device-name 'Tablet B' --input test.h264` | Tahtada "Tablet B ekranını paylaşmak istiyor" diyaloğu, 20 sn geri sayım | ☐ |
| 8 | Diyalogda **İzin ver** | Eski akış biter, yenisi görünür | ☐ |
| 9 | 7'yi tekrarlayıp **Reddet** | Diyalog kapanır, eski akış sürer; ikinci gönderici `BUSY_DENIED` | ☐ |
| 10 | Alıcı açıkken menüden ikinci kez açın | "Port 47800 kullanımda" uyarısı (çökme yok) | ☐ |

### Sonuç bildirimi

- "Hazırlık 3" komutlarının çıktısı
- Alıcı günlüğü: `yansit-receiver --verbose 2>&1 | tee ~/yansit.log` ile başlatıp denetimlerden sonra
  `~/yansit.log` (özellikle `video boru hattı: … (donanım|yazılım)` satırı)
- Sahte göndericinin son satırındaki JSON özet (`last_c2d_ms`, `receiver_frames_dropped`)
- Başarısız adımların numarası ve mümkünse fotoğraf

---

## M3 — Tablet uygulaması (yalnız görüntü)

Amaç: Gerçek bir tablette QR ile bağlanıp ekranın tahtada akıcı göründüğünü, durdurmanın hızlı olduğunu ve
ayarların yalnız desteklenen değerleri gösterdiğini doğrulamak.

### Hazırlık

1. **Android** iş akışının son başarılı çalışmasından `android-apk` artefaktını indirin
   (`app-debug.apk`; imzalı sürüm M8'de).
2. Tablette **Ayarlar → Güvenlik → Bilinmeyen uygulamalar**'dan kuruluma izin verip APK'yı kurun.
   Bilgisayardan: `adb install -r app-debug.apk`
3. Tahta yoksa bir dizüstü bilgisayar alıcı olabilir (Debian/Ubuntu veya Docker):
   ```bash
   docker build -t yansit-deb12 -f receiver/ci/Dockerfile receiver/ci
   docker run --rm -it --network host -v "$PWD":/src -w /src yansit-deb12 sh -c \
     'receiver/ci/build-and-test.sh /tmp/b >/dev/null && /tmp/b/src/yansit-receiver --headless --verbose --pairing-file /src/p.json'
   ```
   QR yerine `p.json` içindeki `uri` değerini bir QR üreticisiyle (ör. `qrencode -t ansiutf8 "$(jq -r .uri p.json)"`)
   ekrana basıp okutun.
4. Tablet bilgileri: model, Android sürümü (`adb shell getprop ro.build.version.release`), Wi-Fi bandı (2,4/5 GHz).

### Denetimler

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Uygulamayı ilk kez açıp **Tahtaya Bağlan** | İzin ekranı: kamera, (Android 17) yerel ağ, (Android 13+) bildirim — her biri gerekçesiyle | ☐ |
| 2 | **Devam** → izinleri verin | QR tarayıcı açılır | ☐ |
| 3 | Tahtadaki QR'ı okutun | "… tahtasına bağlanılıyor" → sistem "Ekranınız paylaşılsın mı?" penceresi (Android 14+: yalnız "tüm ekran") | ☐ |
| 4 | **Başlat** | 1 sn içinde tablet ekranı tahtada; ana ekranda "… tahtasına yansıtılıyor" ve gecikme/fps/Mbps | ☐ |
| 5 | Tabletin ana ekranına dönüp uygulamalar arasında gezinin, bir video açın | Tahtada akıcı (5 GHz'de 60 fps); kartta fps ≈ 60 | ☐ |
| 6 | Bildirimi açıp **Durdur** | ≤ 1 sn içinde tahta bekleme ekranına döner; uygulamada "Yansıtma durduruldu" | ☐ |
| 7 | Yeniden bağlanıp uygulamadaki **Yansıtmayı Durdur** | ≤ 1 sn içinde durur | ☐ |
| 8 | Yansıtırken tahtada ekrana dokunup **Yansıtmayı durdur** | Tablette "Yansıtma tahtadan durduruldu" | ☐ |
| 9 | Aynı (kullanılmış) QR'ın fotoğrafını okutun | "QR kodu artık geçerli değil" | ☐ |
| 10 | Tahtada Yansıt kapalıyken QR okutun | "Tahtada Yansıt açık değil" | ☐ |
| 11 | Tableti başka bir Wi-Fi ağına bağlayıp okutun | "Tahtaya ulaşılamadı" + hotspot önerisi | ☐ |
| 12 | Sistem penceresinde **İptal** | "Ekran paylaşımına izin verilmedi"; tahta bekleme ekranına döner | ☐ |
| 13 | **Ayarlar** → Gelişmiş | Yalnız tabletin desteklediği çözünürlük/fps/bit hızı seçenekleri görünür | ☐ |
| 14 | **Zayıf ağ** seçip bağlanın | Kartta ≈ 30 fps ve ≤ 5 Mbps | ☐ |
| 15 | Yansıtırken ekranı kilitleyin (Android 15+) | Yansıtma durur; uygulamada "Yansıtma durdu" mesajı, tahta bekleme ekranında | ☐ |

### Sonuç bildirimi

- Tablet modeli, Android sürümü, Wi-Fi bandı
- `adb logcat -s YansitMirror YansitEncoder YansitService` çıktısı (bağlanmadan durdurmaya kadar)
- 4–5. adımda kartta görünen gecikme/fps/Mbps ve çözünürlük
- Başarısız adımların numarası ve ekran görüntüsü

---

## M4 — Dayanıklılık

Amaç: Döndürme, Wi-Fi kopması, duraklatma ve hatırlanan tahtaların gerçek cihazda çalıştığını doğrulamak.
CI'da aynı durumların benzetimi her değişiklikte koşuyor (`tools/integration`: döndürme, kopma + RESUME,
dar bant, kalıcı eşleştirme); burada yalnız gerçek donanımın davranışı denetlenir.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Yansıtırken tableti yataydan dikeye çevirin | ≤ 1 sn içinde tahtada dikey görüntü (kenar boşluklu); `adb logcat -s YansitMirror` içinde `yeniden yapılandırma … ms` | ☐ |
| 2 | Geri yataya çevirin, 5 kez hızlıca döndürün | Her seferinde ≤ 1 sn; uygulama çökmez | ☐ |
| 3 | Yansıtırken tabletin Wi-Fi'ını kapatıp 2 sn sonra açın | Tahtada son görüntü donar, ağ gelince ≤ 3 sn içinde akış sürer; tablette kısa süre "Bağlantı yeniden kuruluyor…" | ☐ |
| 4 | Wi-Fi'ı 20 sn kapalı tutup açın | Tablet tahtayı hatırlıyorsa kendiliğinden yeniden bağlanır (QR gerekmez) | ☐ |
| 5 | **Duraklat** | Tahtada son görüntü + "Yansıtma duraklatıldı"; tablette başka uygulamaya geçince tahtada değişiklik yok | ☐ |
| 6 | **Devam et** | ≤ 1 sn içinde güncel ekran | ☐ |
| 7 | Durdurup ana ekrana dönün | "Hatırlanan tahtalar" altında tahtanın adı | ☐ |
| 8 | Tahtanın adına dokunun | QR'sız, onay penceresinden sonra yansıtma başlar | ☐ |
| 9 | Tahtada `~/.local/share/Yansit/yansit-receiver/trusted.json` dosyasını silip alıcıyı yeniden başlatın, 8'i tekrarlayın | "Tahta bu tableti tanımıyor" mesajı; tahta listeden düşer | ☐ |
| 10 | Tablet ve tahta farklı Wi-Fi ağlarındayken bağlanın | "Tablet ve tahta farklı ağlarda" + hotspot adımları | ☐ |
| 11 | Hotspot adımlarını uygulayın (tablet hotspot, tahta ona bağlı) | Yeni QR ile bağlantı kurulur | ☐ |
| 12 | Kalabalık/2,4 GHz bir ağda 5 dk yansıtın | Görüntü zaman zaman kalitesini düşürür ama gecikme birikmez (kartta gecikme < 300 ms civarı) | ☐ |

**Sonuç bildirimi:** `adb logcat -s YansitMirror YansitEncoder YansitService` çıktısı ve alıcının `--verbose`
günlüğü; 1 ve 3. adımlarda ölçülen süreler.

---

## M5 — Ses

CI'da Opus ve AAC akışları gerçek alıcıda çalınır ve ses–görüntü sapması ölçülür (başsız modda ≈ 22 ms). Burada
tahtanın hoparlöründen gerçek ses ve dudak senkronu denetlenir.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | İzin ekranında "Ses (isteğe bağlı)" iznini verin, bağlanın | Ana ekranda "Ses tahtadan çalıyor." | ☐ |
| 2 | Tablette YouTube veya bir video dosyası oynatın | Ses tahtanın hoparlöründen; tabletten ses gelmez | ☐ |
| 3 | Konuşan bir kişinin olduğu video | Dudak ile ses arasında fark edilir kayma yok (≤ ±60 ms; şüphede M7 ölçüm aracı) | ☐ |
| 4 | **Duraklat** | Tahtada ses de durur | ☐ |
| 5 | **Devam et** | Ses ≤ 1 sn içinde geri gelir | ☐ |
| 6 | Ayarlar → "Sesi tahtaya aktar" kapalı, yeniden bağlanın | Ses tabletten çalar; ana ekranda "Ses kapalı (Ayarlar)." | ☐ |
| 7 | Ses iznini reddedip bağlanın | Görüntü normal; "Ses aktarılmıyor: ses izni verilmedi." | ☐ |
| 8 | Tahtada `yansit-receiver --no-audio` | Tablette "Tahtada ses kapalı; ses tabletten çalar." | ☐ |
| 9 | Wi-Fi'ı 2 sn kesip açın | Görüntüyle birlikte ses de geri gelir | ☐ |
| 10 | 10 dk yansıtma (video oynatırken) | Ses kesilmez, giderek kaymaz | ☐ |

**Sonuç bildirimi:** `adb logcat -s YansitAudio YansitMirror` ve alıcının `--verbose` günlüğü (`yansit.audio`
satırları), 3. ve 10. adımlardaki gözlem.
