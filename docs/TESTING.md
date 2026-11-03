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
