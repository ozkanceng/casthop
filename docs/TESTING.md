# CastHop — Gerçek Cihaz Test Kontrol Listeleri

CI her değişiklikte birim testlerini ve sahte gönderici ↔ alıcı uçtan uca testini (`tools/integration/run.sh`)
çalıştırır. Bu belge CI'ın yapamadıklarını, yani **gerçek tahta ve tabletle** yapılacak denetimleri listeler.

Her listenin sonunda "Sonuç bildirimi" bölümü var: çıktıları bir GitHub issue'suna yapıştırmanız yeterli.

---

## M2 — Tahtada alıcı + sahte gönderici

Amaç: Alıcının Pardus ETAP tahtada açıldığını, donanım video çözücüyü bulduğunu ve görüntüyü tam ekran,
akıcı gösterdiğini doğrulamak. Henüz tablet uygulaması yok; görüntüyü bir bilgisayardaki **sahte gönderici**
yollar.

### Hazırlık

1. [Actions](https://github.com/ozkanceng/casthop/actions) → son başarılı **Receiver** çalışması → `receiver-deb`
   artefaktını indirin. **Integration** çalışmasından `fake-sender` artefaktını indirin.
2. Tahtada kurun:
   ```bash
   sudo apt install ./casthop_*.deb
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
alıcıyı `casthop --pairing-file ~/yansit-pairing.json` ile başlatıp dosyadaki `uri` değerini alın.

### Denetimler

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Menüden **CastHop**'u açın | Tam ekran bekleme ekranı: büyük QR, 6 haneli kod, tahta adı ve IP adresi | ☐ |
| 2 | Ağ kablosunu/Wi-Fi'ı kesip 10 sn bekleyin, geri bağlayın | Önce "Ağ bağlantısı yok" uyarısı, bağlanınca QR geri gelir | ☐ |
| 3 | `fake-sender/bin/fake-sender --uri 'yansit://c?...' --input test.h264 --fps 60 --frames 1800` | 1 sn içinde görüntü tam ekran; top akıcı hareket eder, takılma yok | ☐ |
| 4 | Akış sırasında ekrana dokunun | Üstte gönderici adı ve `… ms · 60 fps · … Mbps` bilgisi, 4 sn sonra kaybolur | ☐ |
| 5 | Akış bitince | Bekleme ekranına dönülür, QR **yenilenmiş** olur (eski QR artık geçmez) | ☐ |
| 6 | Yeni QR ile yeniden başlatın, akarken dokunup **Yansıtmayı durdur** | 1 sn içinde bekleme ekranı; göndericide `bye_reason` 1 | ☐ |
| 7 | Akış sürerken (önce bekleme ekranındaki 6 haneli kodu not edin) ikinci terminalde: `fake-sender --uri '…' --auth pin --pin 123456 --device-name 'Tablet B' --input test.h264` | Tahtada "Tablet B ekranını paylaşmak istiyor" diyaloğu, 20 sn geri sayım | ☐ |
| 8 | Diyalogda **İzin ver** | Eski akış biter, yenisi görünür | ☐ |
| 9 | 7'yi tekrarlayıp **Reddet** | Diyalog kapanır, eski akış sürer; ikinci gönderici `BUSY_DENIED` | ☐ |
| 10 | Alıcı açıkken menüden ikinci kez açın | "Port 47800 kullanımda" uyarısı (çökme yok) | ☐ |

### Eski Yansıt kurulumundan yükseltme

Daha önce Yansıt 0.1 (`.deb` ya da taşınabilir paket) kurulmuş bir tahtada:

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Eski Yansıt açıkken yeni `.deb`'i kurun | Eski program kapanır; `~/.local/opt/yansit` silinir | ☐ |
| 2 | Menüyü açın (gerekirse oturumu kapatıp açın) | Menüde yalnız **CastHop**; "Yansıt Alıcı" yok | ☐ |
| 3 | Menüden **CastHop**'u açın | Pencerenin altında yeni sürüm numarası (0.1.2 değil) | ☐ |
| 4 | Eskiden otomatik başlatma açıksa tahtayı yeniden başlatın | Yeni CastHop kendiliğinden açılır | ☐ |

### Sonuç bildirimi

- "Hazırlık 3" komutlarının çıktısı
- Alıcı günlüğü: `casthop --verbose 2>&1 | tee ~/yansit.log` ile başlatıp denetimlerden sonra
  `~/yansit.log` (özellikle `video boru hattı: … (donanım|yazılım)` satırı)
- Sahte göndericinin son satırındaki JSON özet (`last_c2d_ms`, `receiver_frames_dropped`)
- Başarısız adımların numarası ve mümkünse fotoğraf

---

## Siyah ekran denetimi (gerçek tahta)

Yansıtma sürüp görüntü gelmiyorsa `~/.cache/yansit/receiver.log` dosyasına bakın:

| Günlük satırı | Anlamı |
|---|---|
| `qml6glsink eklentisi: yok` ve `qml6glsink yüklenemedi: …` | GL eklentisi yüklenmiyor, kareler işlemciyle çizilir; nedeni satırda |
| `GL: …` | Tahtanın GL sürücüsü |
| `görüntü denetimi: çözülen parlaklık X pencere parlaklığı Y` | X kareyi, Y pencereyi ölçer (siyah: X < 24, Y < 4) |
| `CPU çizime geçiliyor` | GL siyah çiziyordu, işlemci çizimine geçildi |
| `çözülen kareler siyah` | Tablet siyah görüntü gönderiyor olabilir |
| `görüntü çiziliyor` ama ekran siyah | Pencere içeriği doğru, sorun masaüstü bileşicisinde olabilir: **Ayarlar → Uyumlu görüntü modu** |

Yansıtma başı (gerçek tablet ve tahta):

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Alıcıyı yeniden başlatın, tabletten yansıtmayı başlatın, hiçbir şeye dokunmayın | Görüntü duraklatıp devam etmeden 5 sn içinde gelir | |
| 2 | Günlükte `CPU çizime geçiliyor` varsa alıcıyı kapatıp açın ve yeniden yansıtın | **Ayarlar → Uyumlu görüntü modu** açık, görüntü siyah başlamaz | |

## Gecikme karşılaştırması (gerçek tahta ve tablet)

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Sabit ekranda bir düğmeye dokunup tahtayı 240 fps video ile çekin | Tepki süresi önceki sürümden kısa | ☐ |
| 2 | Tablette `adb shell dumpsys wifi \| grep -i -A3 'lock'` | Yansıtırken `yansit:mirror` kilitleri görünür | ☐ |
| 3 | Yansıtırken başka bir uygulamaya geçip aynı komutu çalıştırın | Kilit etkin kalır (Android 14+ için doğrulanacak) | ☐ |
| 4 | Tahtada `vah264dec` ile 5 dk video oynatın | Kare sırası bozulmaz, görüntü titremez | ☐ |

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
     'receiver/ci/build-and-test.sh /tmp/b >/dev/null && /tmp/b/src/casthop --headless --verbose --pairing-file /src/p.json'
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
| 10 | Tahtada CastHop kapalıyken QR okutun | "Tahtada CastHop açık değil" | ☐ |
| 11 | Tableti başka bir Wi-Fi ağına bağlayıp okutun | "Tahtaya ulaşılamadı" + hotspot önerisi | ☐ |
| 12 | Sistem penceresinde **İptal** | "Ekran paylaşımına izin verilmedi"; tahta bekleme ekranına döner | ☐ |
| 13 | **Ayarlar** → Gelişmiş | Yalnız tabletin desteklediği çözünürlük/fps/bit hızı seçenekleri görünür | ☐ |
| 14 | **Zayıf ağ** seçip bağlanın | Kartta ≈ 30 fps ve ≤ 5 Mbps | ☐ |
| 15 | Yansıtırken ekranı kilitleyin (Android 15+) | Yansıtma durur; uygulamada "Yansıtma durdu" mesajı, tahta bekleme ekranında | ☐ |
| 16 | Yansıtırken **Ayarlar**'a dokunun, sonra geri dönün | Ayarlar açılır, üstte "Değişiklikler bir sonraki yansıtmada geçerli olur."; yansıtma kesilmez; geri dönünce yansıtma ekranı | ☐ |

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
| 1 | Yansıtırken tableti yataydan dikeye çevirin | ≤ 1 sn içinde tahtada dikey görüntü (kenar boşluklu); `adb logcat -s YansitScreen` içinde `kaynak: … → …` ve `görüntü …`; `YansitEncoder` içinde yeni `kodlayıcı:` satırı yok | ☐ |
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
| 13 | Ağı bit hızı tabana inecek kadar zayıflatın (uzaklaşın veya ağı yükleyin), 5 sn bekleyin | Tablette "Ağ zayıf: kalite otomatik düşürüldü"; çözünürlük kartta aynı kalır, kare hızı ve veri hızı düşer; oturum kopmaz, görüntü siyah kalmaz | ☐ |
| 14 | Geçiş anındaki görüntü kesintisini ölçün | Kısa kesinti; süreyi not edin (eşikler tasarım tahmini, ayar gerekebilir) | ☐ |
| 15 | Kalite düşükken tableti döndürün | Görüntü döner, kalite seçili ön ayara dönmez | ☐ |
| 16 | Ağı düzeltip en az 70 sn bekleyin | Kalite kendiliğinden bir basamak yükselir, uyarı kalkar; seçilen ön ayarı aşmaz | ☐ |
| 17 | Ayarlar → Gelişmiş'te elle değer verip 13. adımı tekrarlayın | Ön ayar değişmez, yalnız bit hızı düşer | ☐ |
| 18 | Ayarlar'dan **En iyi kalite**'yi seçip okulun yavaş Wi-Fi'ında yansıtmayı başlatın | Donma en çok ~10 sn sürer; kalite Zayıf ağ'a kadar iner; logcat'te `kalite:` satırları 5 sn arayla | ☐ |
| 19 | 18'den sonra durdurup aynı tahtaya yeniden bağlanın | Yansıtma son inilen ön ayardan başlar (logcat `yansıtma: … WEAK_NETWORK`), başta donma olmaz | ☐ |
| 20 | Telefonun hotspot'una geçip aynı tahtaya bağlanın, 2–3 dk bekleyin | Kalite dakikada bir basamak yükselir, seçilen ön ayarı aşmaz; sonraki bağlantı yükselen ön ayardan başlar | ☐ |
| 21 | Samsung Exynos tablette (ör. SM-X610) **En iyi kalite** ile 4K alıcıya (Mac, 4K tahta) 10 kez bağlanıp durdurun | Tablet yeniden başlamaz; logcat `yansıtma: 1920x1200@…`, 2560 görünmez | ☐ |
| 22 | Ağ koparken (Wi-Fi'ı 3 sn kapatıp açın) hemen **Durdur**'a basın, 5 kez tekrarlayın | Uygulama kapanmaz (`RejectedExecutionException` yok) | ☐ |
| 23 | Uygulamayı kaldırıp yeniden kurun, Ayarlar'ı açın | Görüntü kalitesinde **Dengeli** seçili | ☐ |
| 24 | Samsung Exynos tablette (ör. SM-X610) **En iyi kalite** ile yavaş Wi-Fi'da yansıtmayı 10 kez başlatın; kalite her seferinde Zayıf ağ'a insin | Tablet yeniden başlamaz; `adb shell dumpsys dropbox \| grep LAST_KMSG` yeni kayıt göstermez; basamaklarda `YansitEncoder` yeni `kodlayıcı:` satırı yazmaz, görüntü kesilmez | ☐ |
| 25 | Yansıtırken tableti 10 kez döndürün (dikey ↔ yatay) | Tablet yeniden başlamaz; tahtada görüntü her seferinde yeni yöne geçer, siyah kalmaz, akış kesilmez (tahtada yeniden başlama/IDR beklemesi yok) | ☐ |
| 26 | Yansıtırken **Duraklat**, beklerken kaliteyi düşürecek kadar ağı zayıflatın, sonra **Devam** | Duraklatmada tahtada son kare kalır; devamda görüntü yeni boyutta gelir | ☐ |
| 27 | 2,4 GHz ağda kalite kendiliğinden düşsün | Tablette "Ağ zayıf…" altında "Tablet 2,4 GHz Wi-Fi'da…" önerisi; 5 GHz ağda bu öneri çıkmaz | ☐ |
| 28 | Tablet dik tutulurken yansıtmayı başlatın | Tahtada yatay kare, dikey görüntü ortada, iki yanda siyah; logcat `yansıtma: 1920x1200@…` (yatay) | ☐ |
| 29 | Küçük yazılı bir sayfayı (ör. PDF dipnotu) 0.3.9 ile ve bu sürümle yansıtın | Yazı en az aynı keskinlikte; kartta gecikme 0.3.9'dan yüksek değil | ☐ |
| 30 | Zayıf ağ'a inmiş bir tahtaya yeniden bağlanın | logcat `yansıtma:` satırında boyut seçilen ön ayarınki (ör. Dengeli 1600x1000), kare hızı 30; ağ iyiyse 60'a çıkar | ☐ |
| 31 | Zayıf ağ basamağında 1 dk yansıtın | Kartta veri hızı Zayıf ağ aralığında (≤ 5 Mbps), görüntü akıcı; hedeften çok düşükse not edin | ☐ |

**Sonuç bildirimi:** `adb logcat -s YansitMirror YansitEncoder YansitService` çıktısı ve alıcının `--verbose`
günlüğü; 1 ve 3. adımlarda ölçülen süreler.

---

## Kamera yansıtma (gerçek iPhone ve iPad)

Simülatörde kamera yoktur; bu liste gerçek cihaz ister.

| # | Adım | Beklenen | Sonuç |
|---|---|---|---|
| 1 | Bir alıcı seçip "Kamerayı yansıt"a dokunun | Sistem yayın penceresi çıkmaz; önizleme ve alıcıda görüntü gelir | ☐ |
| 2 | Cihazı dikey ve yatay çevirin | İki uçta da görüntü dik kalır, oran bozulmaz | ☐ |
| 3 | "Kamerayı değiştir" | Ön kamera gelir, görüntü dik ve yayın kopmaz | ☐ |
| 4 | Duraklat, sonra Devam | Alıcıda son görüntü kalır, devamda canlı görüntü döner | ☐ |
| 5 | iPad'de Split View'da başka uygulama açın | Kamera duraklar, tam ekrana dönünce sürer | ☐ |
| 6 | Uygulamadan çıkın | Yayın biter, geri dönünce "Kamera yansıtma durdu" görünür | ☐ |
| 7 | Kamera iznini kapatıp deneyin | "Kamera izni kapalı" ve Ayarlar yönergesi görünür | ☐ |
| 8 | 10 dk yansıtın | Ekran kararmaz, cihaz aşırı ısınmaz, gecikme birikmez | ☐ |

## Mac `.dmg`

| # | Adım | Beklenen | Sonuç |
|---|---|---|---|
| 1 | `casthop-mac.dmg`'yi başka bir Mac'e indirip uygulamayı Applications'a sürükleyin | Uyarı çıkar, Gizlilik ve Güvenlik → "Yine de Aç" ile açılır | ☐ |
| 2 | Intel Mac'te açın | Evrensel paket açılır, görüntü gelir | ☐ |
| 3 | Yerel ağ izni sorusuna izin verin | Tablet aynı ağda alıcıyı listeler | ☐ |

## Kamera yansıtma (gerçek tablet)

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Ana ekranda "Kamerayı yansıt"ı açıp bağlanın | Ekran paylaşım onayı çıkmaz; tablette ve alıcıda kamera görüntüsü, yatay ve baş aşağı değil | ☐ |
| 2 | Tablette önizlemeye bakın | Görüntü dik ve doğru oranda; telefon ve doğal yönü yatay tabletlerde ayrı ayrı denetleyin | ☐ |
| 3 | "Kamerayı değiştir" | Ön kamera gelir, yayın kopmaz | ☐ |
| 4 | Duraklat, devam et | Alıcıda son görüntü kalır, ≤ 1 sn içinde görüntü döner | ☐ |
| 5 | Uygulamayı arka plana alıp geri dönün | Yayın sürer, önizleme geri gelir | ☐ |
| 6 | Wi-Fi'ı 2 sn kapatıp açın | Kendiliğinden devam eder | ☐ |

---

## CastHop adına geçiş (gerçek tahta ve tablet)

Paket yükseltmesi temiz Debian 12 kapsayıcısında denenir. Burada gerçek masaüstü ve tablet güncellemesi denetlenir.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | v0.1.2 kurulu tahtada "Tahta açılınca başlat"ı açın, yeni `.deb` kurun | Menüde tek CastHop; eski ad görünmez | ☐ |
| 2 | Tahtayı yeniden başlatın | CastHop kendiliğinden açılır | ☐ |
| 3 | `kur` ile kurulu tahtada 1 ve 2. adımları `kur` komutuyla tekrarlayın | Aynı sonuç; "Tahta açılınca başlat" çalışır | ☐ |
| 4 | Tablet uygulamasını yeni APK ile güncelleyin (silmeden) | Uygulama CastHop adıyla açılır, hatırlanan tahtalar yerinde | ☐ |
| 5 | Eski tablet uygulamasıyla yeni tahtaya bağlanın | Bağlantı ve yansıtma çalışır | ☐ |

---

## M5 — Ses

CI'da Opus ve AAC akışları gerçek alıcıda çalınır ve ses–görüntü sapması ölçülür (başsız modda ≈ 22 ms). Burada
tahtanın hoparlöründen gerçek ses ve dudak senkronu denetlenir.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 0 | Uygulamayı ilk kez kurup bağlanın | İzin ekranında ses izni yok; ana ekranda "Ses kapalı (Ayarlar)."; ses tabletten çalar | ☐ |
| 1 | Ayarlar → "Sesi ekrana aktar"ı açın | Android ses iznini sorar; izni verin, yeniden bağlanın: ana ekranda "Ses ekrandan çalıyor." | ☐ |
| 2 | Tablette YouTube veya bir video dosyası oynatın | Ses tahtanın hoparlöründen; tabletten ses gelmez | ☐ |
| 3 | Konuşan bir kişinin olduğu video | Dudak ile ses arasında fark edilir kayma yok (≤ ±60 ms; şüphede M7 ölçüm aracı) | ☐ |
| 4 | **Duraklat** | Tahtada ses de durur | ☐ |
| 5 | **Devam et** | Ses ≤ 1 sn içinde geri gelir | ☐ |
| 6 | Ayarlar → "Sesi ekrana aktar"ı kapatıp yeniden bağlanın | Ses tabletten çalar; ana ekranda "Ses kapalı (Ayarlar)." | ☐ |
| 7 | Ayarı açıp ses iznini reddedin, bağlanın | Görüntü normal; "Ses aktarılmıyor: ses izni verilmedi." | ☐ |
| 8 | Tahtada `casthop --no-audio` | Tablette "Tahtada ses kapalı; ses tabletten çalar." | ☐ |
| 9 | Wi-Fi'ı 2 sn kesip açın | Görüntüyle birlikte ses de geri gelir | ☐ |
| 10 | 10 dk yansıtma (video oynatırken) | Ses kesilmez, giderek kaymaz | ☐ |

**Sonuç bildirimi:** `adb logcat -s YansitAudio YansitMirror` ve alıcının `--verbose` günlüğü (`yansit.audio`
satırları), 3. ve 10. adımlardaki gözlem.

---

## M6 — Ayarlar, ağda bulma, H.265

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Tahtada **Ayarlar** → adı "7-A Sınıfı" yapıp **Kaydet** | Bekleme ekranında yeni ad; QR yenilenir | ☐ |
| 2 | Tema **Açık** | Arayüz açık renge geçer, yazılar okunaklı | ☐ |
| 3 | **Doldur (kenarları kırp)** ile dikey tableti yansıtın | Görüntü ekranı doldurur (üst/alt kırpılır), bozulma yok; **Sığdır** ile kenar boşluklu | ☐ |
| 4 | "Tahta açılınca CastHop'u başlat" açıp tahtayı yeniden başlatın | Oturum açılınca CastHop tam ekran gelir | ☐ |
| 5 | Yansıtırken ekrana dokunup **Ekran görüntüsü** | "Ekran görüntüsü kaydedildi: …/Resimler/CastHop/…png"; dosya açılınca tablet görüntüsü (bilgi çubuğu yok) | ☐ |
| 6 | **Eşleştirilmiş tabletler** → bir tableti **Unut** | O tablet bir sonraki bağlanışta "Tahta bu tableti tanımıyor" alır | ☐ |
| 7 | Tablette **Kodla bağlan (QR olmadan)** | Android 17: sistemin tahta seçicisi; daha eskide "Ağdaki tahtalar" listesi | ☐ |
| 8 | Tahtayı seçip ekrandaki 6 haneli kodu girin | Bağlanır; tahta "Hatırlanan tahtalar"a eklenir | ☐ |
| 9 | Yanlış kod girin | "Kod yanlış"; 5 yanlıştan sonra tahtada kod değişir | ☐ |
| 10 | Okul ağında `avahi-daemon` kapalıyken (`sudo systemctl stop avahi-daemon`) | Tahta listede görünmez; QR ile bağlanma normal çalışır | ☐ |
| 11 | Tabletin H.265 donanım kodlayıcısı varsa Ayarlar → "H.265 kullan" | Tahta VA ile H.265 çözüyorsa günlükte `vah265dec`; değilse H.264 sürer | ☐ |
| 12 | Yansıtırken bağlantı kalitesi etiketi | 5 GHz'de "Bağlantı: iyi"; kalabalık ağda "orta/zayıf" | ☐ |

**Sonuç bildirimi:** `vainfo` çıktısında HEVC satırları; `avahi-browse -rt _yansit._tcp` çıktısı (tahtada veya aynı
ağdaki bir Linux bilgisayarda); 3. ve 5. adımların fotoğrafı.

---

## M7 — Gecikme ölçümü ve uzun süre

CI her gece 30 dk'lık uzun süre testini koşar (`soak.yml`; gecikme eğimi, bellek, kayıp). Gecikme deseninin okunması
ve ölçümü her değişiklikte entegrasyon testinde denetlenir. Burada **gerçek donanımda** uçtan uca gecikme ölçülür.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | 5 GHz Wi-Fi, **En iyi kalite**, yansıtma başlasın; tablette Ayarlar → **Gecikme ölçümü → Ölçüm ekranını aç** | Tablette üstte siyah-beyaz bant ve büyük saat | ☐ |
| 2 | Tahtada ekrana dokunun | Bilgi çubuğunda `uçtan uca … ms (p95 …)`; hedef ≤ 80 ms, kabul ≤ 120 ms | ☐ |
| 3 | 1 dk bekleyip değeri not edin | ortalama: ____ ms, p95: ____ ms | ☐ |
| 4 | Telefonla tableti ve tahtayı aynı karede 10 kez fotoğraflayın | İki saat arasındaki farkların ortancası: ____ ms (panel gecikmesi dahil) | ☐ |
| 5 | 2. ve 4. adımların farkı | ≈ tahta panelinin gecikmesi (RISKS R8); büyükse tahtada "oyun modu"/düşük gecikme ayarı aranır | ☐ |
| 6 | 30 dk yansıtma (ölçüm ekranı açık) | Değer giderek artmaz; tahtada `ps -o rss= -C casthop` başta/sonda: ____ / ____ MB | ☐ |
| 7 | Aynı ölçümü **Dengeli** ve 2,4 GHz ağda tekrarlayın | sonuç: ____ ms | ☐ |

**Sonuç bildirimi:** tablet modeli, tahta modeli (CPU, `vainfo`), Wi-Fi bandı/kanalı, ölçülen değerler ve fotoğraflar.
README'deki "Ölçülen performans" tablosu bu sonuçlarla güncellenir.

---

## Android TV alıcısı (gerçek televizyon ve kumanda)

`android/tv` emülatörde çalıştırılabilir; ama donanım çözücü, gerçek ses çıkışı, kumanda ve mDNS orada sınanamaz.
Hata ayıklama derlemesinde eşleşme bilgisi `adb shell am broadcast -n io.github.ozkanceng.casthop.tv/.DebugTestReceiver`
ile `files/test/pairing.json` dosyasına yazılır (`adb exec-out run-as io.github.ozkanceng.casthop.tv cat files/test/pairing.json`).

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Android TV'ye APK'yı kurun, TV ana ekranına bakın | Uygulama afişi (banner) okunaklı görünür, uygulama TV başlatıcısından açılır | ☐ |
| 2 | Bekleme ekranına 3 m uzaktan bakın | Başlık, kod ve QR okunur; tabletin kamerası QR'ı okur | ☐ |
| 3 | Yalnız kumandayla Ayarlar'ı açın, ad değiştirin, sesi kapatıp açın, Geri ile dönün | Odak her zaman görünür, hiçbir yerde takılı kalmaz; ad alanından aşağı okla çıkılır | ☐ |
| 4 | Aynı ağdaki tabletten yansıtın | Tahta listesinde TV'nin adı görünür (mDNS); bağlantı QR'sız da kurulur | ☐ |
| 5 | 10 dk yansıtın, kumandada Tamam'a basın | Çözücü adı donanım çözücüsüdür; gecikme ve kare hızı görünür, Yansıtmayı Durdur çalışır | ☐ |
| 6 | Tablette video oynatın | Ses TV hoparlöründen duyulur, dudak senkronu kaymaz; HDMI/ARC kullanılıyorsa ayrıca deneyin | ☐ |
| 7 | Tableti döndürün | Görüntü oranı korunarak yeniden çizilir | ☐ |
| 8 | Yansıtırken ikinci tabletle bağlanın | Onay penceresi çıkar, odak Reddet'tedir; iki seçenek de doğru çalışır | ☐ |
| 9 | TV'yi yalnız kabloyla ağa bağlayın (Wi-Fi'siz model varsa) | Uygulama kurulur, yansıtma çalışır | ☐ |
| 10 | Yansıtırken Home ile çıkın, geri dönün | Oturum biter, gönderici haberdar olur; dönüşte yeni QR ve kod görünür | ☐ |
| 11 | Android 17 cihazda ilk açılış | Yerel ağ izni sorulur; reddedilince uyarı ve İzin Ver düğmesi görünür, izin verilince bağlantı kurulur | ☐ |
| 12 | Dokunmatik Android tahta veya tablette çalıştırın | Aynı APK LAUNCHER'dan açılır; düğmeler ve yansıtma denetimleri dokunmayla çalışır | ☐ |

**Sonuç bildirimi:** cihaz modeli, Android sürümü, çözücü adı, gecikme, ses gözlemi.

---

## Windows alıcısı (gerçek bilgisayar)

CI (`.github/workflows/windows.yml`) kurucuyu kurar, uyum testini kurulu programa karşı çalıştırır ve kaldırır. Sunucu
işletim sisteminde ekran kartı, ses aygıtı ve Wi-Fi olmadığı için aşağıdakiler gerçek Windows 10 ve 11 bilgisayarda denenir.

### Kurulum

- [ ] SmartScreen uyarısı ve imzasız kurucunun davranışı not edildi.
- [ ] Kurulum Türkçe Windows'ta Türkçe, İngilizce Windows'ta İngilizce açılıyor.
- [ ] Kullanıcı adında Türkçe karakter olan hesapta (`C:\Users\Özkan`) program açılıyor, sertifika ve eşleştirme korunuyor.
- [ ] Başlat menüsünde CastHop var ve simgesi görünüyor.
- [ ] `Get-NetFirewallRule -DisplayName 'CastHop*'` iki kural gösteriyor; ilk bağlantıda güvenlik duvarı sorusu çıkmıyor.
- [ ] Ağ profili Ortak iken de tablet bağlanıyor.
- [ ] `icacls %APPDATA%\Yansit\yansit-receiver\identity\key.pem` yalnız geçerli kullanıcıyı gösteriyor.
- [ ] Üstüne yeniden kurulum eşleştirmeleri koruyor, kural sayısı artmıyor.
- [ ] Program açıkken kaldırma sorunsuz bitiyor; "ayarlar da silinsin mi" sorusu iki yanıtla da doğru davranıyor.
- [ ] Ayarlar → otomatik başlatma: oturum yeniden açılınca CastHop başlıyor; kapatınca veya kaldırınca `Run` değeri siliniyor.

### Görüntü ve ses

- [ ] Intel, AMD ve NVIDIA ekran kartında `%LOCALAPPDATA%\cache\yansit\receiver.log` içinde `d3d11h264dec (donanım)` görünüyor.
- [ ] Donanım çözücüsü olmayan makinede (uzak masaüstü, sanal makine) `avdec_h264` ile görüntü geliyor.
- [ ] H.265 yalnız donanım çözücüsü varken tabletlerde seçilebiliyor.
- [ ] 1080p60 yansıtmada işlemci çiziminin işlemci kullanımı ve gecikmesi not edildi; kare kaybı birikmiyor.
- [ ] Tablet döndürülünce görüntü doğru yönde, siyah ekran yok.
- [ ] Ses duyuluyor ve görüntüyle aynı anda; kulaklık takılıp çıkarılınca ses sürüyor.
- [ ] Yüksek DPI ekranda (%150, %200) arayüz ve QR net, dokunma hedefleri yeterince büyük.
- [ ] Dokunmatik ekranlı bilgisayarda düğmeler çalışıyor.

### Ağ

- [ ] Tablet, QR'sız listede bilgisayarı görüyor ve 6 haneli kodla bağlanıyor (`DnsServiceRegister`). Adın sonunda nokta olan bilgisayarda da.
- [ ] Hyper-V, WSL veya VPN bağdaştırıcısı varken QR doğru (Wi-Fi veya Ethernet) adresi taşıyor.
- [ ] Bilgisayar uyku modundan dönünce alıcı yeniden bağlantı alıyor.
- [ ] Windows kapatılırken yansıtma sürüyorsa tablet "alıcı kapandı" görüyor.

Sonuçları ve `receiver.log` dosyasını bir GitHub issue'suna ekleyin.

---

## Apple — iPad/iPhone gönderici, Mac/Apple TV/iPad alıcı

Simülatörde ReplayKit yayını çalışmaz. Paket testleri, uyum testi (`tools/conformance/run.py`) ve `casthop-sender` ile
`casthop-receiver` uçtan uca Mac'te koşar; `BroadcastEngine` da aynı komutla (`--broadcast-store`) denenir. Aşağıdakiler
yalnız **gerçek cihazda ve imzalı derlemede** denetlenebilir. Takım kimliği depoda yoktur: Xcode'da yerel olarak seçilir.

### Hazırlık

```
cd apple && xcodegen generate && open CastHop.xcodeproj
```

`SenderApp` ve `BroadcastExtension` hedeflerine aynı takımı ve `group.com.sorumatik.casthop` App Group'unu verin.

### Gönderici

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Uygulamayı ilk kez açın | Yerel Ağ izni istenir; reddedilince ekranda ne yapılacağı yazar | ☐ |
| 2 | Alıcının QR'ını kamerayla okutun | Kamera izni istenir, eşleşme biter, "Hazır" ekranı açılır | ☐ |
| 3 | Bul ekranından alıcıyı seçip PIN girin | Bonjour alıcıyı listeler; eşleşme biter | ☐ |
| 4 | Uygulamayı kapatıp açın | Hatırlanan alıcı duruyor; uzantı ile durum paylaşılıyor (App Group) | ☐ |
| 5 | Yayın düğmesi → **Yayını Başlat** | Sistem seçicisinde yalnız CastHop seçili; alıcıda görüntü gelir | ☐ |
| 6 | Cihazı dört yöne döndürün | Görüntü her yönde doğru açıda; `RPVideoSampleOrientationKey` eşlemesi ters değil | ☐ |
| 7 | Video oynatan bir uygulama açın | Uygulama sesi alıcıda duyulur, hızlı veya cızırtılı değil (ReplayKit sesi büyük uçlu gelebilir) | ☐ |
| 8 | Alıcı istatistiklerine bakın | Gecikme zamanla artmıyor; zaman damgaları ana saatle tutarlı | ☐ |
| 9 | 30 dk en iyi kalitede yayın; Xcode → Debug Navigator ile uzantı belleği | Uzantı 50 MB altında kalır, sistem kapatmaz: en yüksek ____ MB | ☐ |
| 10 | Yayın sırasında Wi-Fi'ı 5 sn kesin | 10 sn içinde RESUME ile görüntü döner | ☐ |
| 11 | Wi-Fi'ı 20 sn kesin | Yayın anlaşılır hatayla biter; uygulamada neden yazar | ☐ |
| 12 | Yayını Denetim Merkezi'nden durdurun | Alıcı bekleme ekranına döner; uygulama "Hazır" durumuna geçer | ☐ |
| 13 | Tıkalı ağda yayın (Network Link Conditioner ile 3 Mbps) | Gecikme birikmez; Network.framework gönderim tamponu kuyruğu büyütmüyor | ☐ |
| 14 | 13'teki ağda yayını durdurup aynı alıcıya yeniden başlatın | Yayın son inilen ön ayardan başlar, başta donma olmaz; ağ açılınca dakikada bir basamak yükselir | ☐ |

### Alıcı

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Mac: uygulamayı açın, pencereye bakın | Bekleme ekranı QR ve PIN ile 3 m'den okunur; Tam ekran çalışır | ☐ |
| 2 | Mac: ilk açılış | Yerel Ağ ve Güvenlik Duvarı sorusu çıkar, izin sonrası bağlantı gelir | ☐ |
| 3 | Mac: Android ve iPad ile yansıtma | Donanım çözücü açık (`hardware`), kare düşmüyor | ☐ |
| 4 | Apple TV: uzaktan kumandayla Ayarlar'a girip çıkın | Odak halkası görünür, tüm öğelere ulaşılır | ☐ |
| 5 | Apple TV: yansıtma sırasında kumandaya basın | Denetimler açılır, odak **Yansıtmayı durdur** üzerinde; Menu kapatır | ☐ |
| 6 | Apple TV: ikinci gönderici bağlansın | Onay penceresi çıkar, odak **İzin ver** üzerinde, kumandayla seçilir | ☐ |
| 7 | iPad: yatay/dikey döndürün | QR ve kod ekrana sığar; video kesilmez | ☐ |
| 8 | Apple TV ve iPad: uygulamayı yeniden başlatın | Parmak izi aynı kalır; eşleşmiş gönderici yeniden QR istemez | ☐ |
| 9 | Opus gönderen Android ile ses | AudioToolbox çözer; cihaz çözemezse AAC seçilir | ☐ |

**Sonuç bildirimi:** cihaz modeli, sistem sürümü, ağ, ölçülen bellek ve gecikme değerleri.

## Yeni simgeler (gerçek cihaz)

- [ ] Android tablette başlatıcı simgesi yuvarlak ve kare maskede kesilmeden görünüyor.
- [ ] Android 13 ve üstünde temalı simge açıkken tek renkli simge okunuyor.
- [ ] Yansıtma bildiriminde durum çubuğu simgesi C ve nokta olarak seçiliyor.
- [ ] Android TV ana ekranında afiş tam görünüyor, yazı kesilmiyor.
- [ ] Apple TV'de simgenin iki katmanı odaklanınca doğru hareket ediyor.
- [ ] Mac Dock'ta, iPhone ve iPad ana ekranında simge kenar boşluğu doğru görünüyor.
- [ ] Windows kurucusunda, Başlat menüsünde ve görev çubuğunda simge net görünüyor.
- [ ] Pardus menüsünde ve tahta görev çubuğunda simge net görünüyor.

## Site ve `kur` dil desteği (gerçek cihaz)

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Siteyi tahtanın tarayıcısında (Türkçe) ve tablette (dili İngilizce) açın | Türkçe sistemde Türkçe, İngilizce sistemde İngilizce açılır; üstteki düğme dili değiştirir, yenileyince seçim kalır | ☐ |
| 2 | Tahtada `kur` komutunu `LANG=en_US.UTF-8` ile ve varsayılan dille çalıştırın | Mesajlar sırasıyla İngilizce ve Türkçe; kurulum ikisinde de tamamlanır | ☐ |

## Google Play sürümü (gerçek cihaz)

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Play'den `com.sorumatik.casthop` kurulu (GitHub APK'sı kurulu değil) bir tablette uygulamayı açın | Açılır, sürüm 0.3.3 veya üstü, simge yeni logo | ☐ |
| 2 | Pardus tahtayı QR ile eşleştirip yansıtın | Görüntü ve ses gelir; bildirimde «Durdur» var | ☐ |
| 3 | Kamera yansıtmayı açıp bir ekrana bağlanın | Kamera görüntüsü gelir, uygulama arka plana alınınca sürer | ☐ |
| 4 | Eski paket adıyla (`io.github.ozkanceng.yansit`) kurulu bir tabletle aynı tahtaya bağlanın | İkisi de çalışır (protokol aynı) | ☐ |

## Dikey görüntü, durağan ekran ve ısınma (gerçek cihaz)

Mac alıcı, `fake-receiver` ve Pardus başsız alıcısı uyum testinde (`rotated frames`) ve SM-X610 + Mac'te ölçüldü (aşağıda). Gerçek tahta (Pardus GL), Windows, Android TV cihazı ve Apple TV'de denenmedi.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Tableti dikey tutup Pardus tahtaya yansıtın (yeni alıcı) | Görüntü dikey, doğru yönde (üst kenar yukarıda), ekran yüksekliğini doldurur; yazı yatay tutulan tabletteki kadar net | ☐ |
| 2 | Yansıtırken tableti yatay ↔ dikey döndürün (5 kez) | Her dönüşte görüntü yönü değişir; ters, yanlamasına ya da bir an yanlış yönde kare görülmez | ☐ |
| 3 | 1 ve 2'yi Windows alıcısında, Android TV'de ve Apple TV'de / Mac uygulamasında yineleyin | Aynı sonuç; TV'de `TextureView` ile gecikme önceki `SurfaceView` sürümünden belirgin yüksek değil (istatistik satırı) | ☐ |
| 4 | Dikey tableti eski sürüm (0.3.9) alıcıya bağlayın | Görüntü önceki gibi ortada, iki yanı siyah; çevrilmiş görüntü gelmez | ☐ |
| 5 | Durağan bir slayt açıp 1 dk bekleyin, sonra sayfayı kaydırın | Tahta güncel; istatistikte durağanken ~2 kare/sn, kaydırınca hemen 60 kare/sn | ☐ |
| 6 | Yansıtmayı başlatıp ilk 5 sn'yi izleyin | Görüntü hemen gelir; birkaç saniye içinde 30 → 60 kare/sn (tablet günlüğünde `kare hızı: 60`) | ☐ |
| 7 | Kalabalık 2,4 GHz ağda 10 dk yansıtın | Donmalar önceki sürümden az; görüntü kendiliğinden düşük kaliteye inse de akıcı | ☐ |

**Ölçümler (SM-X610 → Mac `casthop-receiver --dump-video`, 2026-11-10):**
- Durağan ekran: önceki sürüm 0 kare/sn (Exynos kodlayıcı tekrar karesi üretmedi), yeni 2,0–2,1 kare/sn, kare başına 106–185 bayt. Hareketli video: 59,9 → 60,0 kare/sn, 11,8 → 11,9 Mbit/s.
- Tıkanıklık (alıcıya 2 sn'de bir 400 ms SIGSTOP, 30 sn): IDR 4 → 1, alınan bit hızı 1,70 → 2,97 Mbit/s, 40,3 → 41,2 kare/sn.
- Isınma: 30 → 60 kare/sn geçişi 7,6 sn'de.
- 30 kare/sn'de bit hızı: kare boyutu iki katına çıkıyor (~33 KB, 8 Mbit/s hedefte ~7,9 Mbit/s); düzeltme gerekmedi.
- Yazı netliği: ince yazılı Ayarlar ekranı, dump karesi ile ekran görüntüsünün küçültülmüşü arasında SSIM 0,9973 (Lanczos) / 0,9981 (alan); GL süper örnekleme eklenmedi.

## EasyCast karşılaştırması (Mac alıcı; gerçek tahtada yinelenecek)

**Yöntem:**
- Tablette milisaniye gösteren bir saat sayfası açılır (Chrome, `Date.now() % 1e6`). Mac'te Mac saatini gösteren bir pencere açılır.
- Tek bir Mac ekran görüntüsünde alıcıdaki tablet saati ve Mac saati okunur.
- İki saatin farkı kalıcı bir `adb shell` üzerinden `date +%s%N` ile ölçülüp düzeltilir; en kısa gidiş-dönüş 12–15 ms, doğruluk ±7 ms.
- Değer tabletin ve Mac'in ekran çizim süresini de içerir; bu pay iki uygulamada aynıdır.
- Veri hızı ve işlemci kullanımı alıcı sürecinde `nettop` ve `ps` ile, 20–30 sn boyunca ölçülür.
- Netlik: tablet Ayarlar ekranı, alıcı tam ekranda; aynı bölge ekran yoğunluğunda yakalanıp `adb exec-out screencap` ile alınan referansla karşılaştırılır (SSIM, gri ton).

**Ölçümler:**
- Cihazlar: SM-X610 tablet (5 GHz Wi-Fi), MacBook Air (kablolu), aynı yerel ağ, 2026-11-10.
- CastHop: alıcı güncel koddan Release, gönderici debug sürümü, Dengeli ayar.
- EasyCast: Android 2.3.1, Mac alıcısı EasyCast Receiver 1.3.5.

| | CastHop (Dengeli, 60 kare/sn) | EasyCast (1080p, 30 kare/sn, HEVC) | EasyCast (1080p, 60 kare/sn, HEVC) |
|---|---|---|---|
| Uçtan uca gecikme, ortanca (12 ölçüm) | ~107 ms (100–129) | ~119 ms (113–141) | ~155 ms (148–161) |
| Kare hızı | 58–61 (istatistik satırı) | 30 (ayar) | 60 (ayar) |
| Yazı netliği: SSIM / kenar oranı | 0,987 / 0,88 | 0,990 / 0,86 | ölçülmedi |
| Veri hızı, sürekli değişen ekran | 7,5 Mbit/s | 2,1 Mbit/s | 6,0 Mbit/s |
| Mac alıcısında işlemci | %10 | %7 | %12 |

- CastHop'un kendi istatistik satırı aynı sırada 29–40 ms gösterdi. Bu değer tabletteki ve Mac'teki ekran çizim süresini içermez.
- Ölçüm arasında CastHop durağan Ayarlar ekranında Zayıf ağ'a (30 kare/sn) indi; 60'a dönmesi 1–2 dk sürdü, bu sürede uçtan uca gecikme ~120 ms ölçüldü. Neden: durağan ekranda saniyede 2 yeniden çizim olduğundan alıcı raporu tek kare taşıyordu; o karenin gecikmesi ya da atılması tıkanıklık sayılıyordu. Düzeltmeden önce 2,5 dk durağan ekranda 17 bit hızı düşüşü (en düşük 3,3 Mbit/s), düzeltmeden sonra 3 dk'da hiç düşüş yok (bit hızı tavanda kaldı).
- H.265 varsayılan olduktan sonra (Mac alıcı donanım H.265 bildirir; tablette `c2.exynos.hevc.encoder`) Dengeli 5,2 Mbit/s başlayıp 4 sn'de 7,8 tavanına çıktı. Saat sayfasında alınan veri 4,95 Mbit/s (H.264'te 7,5): %34 az. Bu turda gecikme ve netlik H.265 ile yeniden ölçülmedi.

| # | Adım | Beklenen | ✓ |
|---|---|---|---|
| 1 | Aynı yöntemi Pardus tahtada iki uygulamayla yineleyin (tahtada saat penceresi + tahtanın 240 fps video kaydı) | CastHop gecikmesi EasyCast'ten yüksek değil | ☐ |
| 2 | Kalabalık 2,4 GHz sınıf ağında ikisini 10'ar dk çalıştırın | CastHop donma sayısı EasyCast'ten fazla değil | ☐ |
| 3 | Tahtada durağan bir slaytı 5 dk yansıtın, sonra slaytı değiştirin | İstatistik satırında kare hızı 60 kalır, kalite Zayıf ağ'a inmez; yeni slayt hemen net gelir | ☐ |
| 4 | Pardus tahtada (VA H.265 çözücü) ve VA'sız tahtada yansıtın, alıcı günlüğünde codec'e bakın | VA H.265 varsa H.265, yoksa H.264 seçilir; ikisinde de görüntü bozulmasız | ☐ |
| 5 | H.265 ile gecikme ve yazı netliğini yukarıdaki yöntemle yeniden ölçün | Gecikme H.264'ten belirgin yüksek değil, SSIM ≥ 0,985 | ☐ |
