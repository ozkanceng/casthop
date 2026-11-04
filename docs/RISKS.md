# Yansıt — Teknik Riskler

Olasılık (O) ve Etki (E): Y = yüksek, O = orta, D = düşük.

| # | Risk | O | E | Çözüm planı | Doğrulama |
|---|---|---|---|---|---|
| R1 | **Okul ağında istemci izolasyonu** (AP'ler cihazların birbirini görmesini engeller) | Y | Y | QR'da birden çok IP; bağlantı hatası sınıflandırma (refused / timeout / farklı alt ağ) → anlaşılır mesaj + **tablet hotspot rehberi**. İleride alıcıda NetworkManager ile hotspot'a bağlanma ekranı. | M4, TESTING.md hotspot senaryosu |
| R2 | **Android 15 QPR1+ ekran kilitlenince yansıtma durur**; her oturum yeniden onay ister (Android 14+) | Y | Y | `onStop()` temiz işlenir, tahta "Tablet kilitlendi" bilgisini gösterir; tablette bildirimden tek dokunuşla "Yeniden başlat" (QR'sız, hatırlanan tahtaya) → yalnız sistem onayı. Yansıtma sırasında ekran zaman aşımı uyarısı + ayara kısayol. Ekranı uyanık tutmak için cihazda wake lock davranışı test edilir (eski API'ler kullanımdan kalkmış olabilir; garanti verilmez). | M4, 🔧 |
| R3 | **Android 17 yerel ağ izni** (`ACCESS_LOCAL_NETWORK`) reddedilirse TCP zaman aşımına düşer, hata belirsiz | O | Y | İlk açılışta gerekçeli izin ekranı; bağlantıdan önce izin kontrolü; reddedilmişse ayarlara yönlendirme. mDNS akışında `NsdManager` picker (izin gerektirmez) yedek yol. | M3, Android 17 cihaz |
| R4 | **ETAP 23 görüntü sunucusu bilinmiyor** (X11/Wayland), GL/EGL ortamı farklı olabilir | O | O | `qml6glsink` her ikisinde çalışır; sorun çıkarsa `RenderCheck` CPU çizime geçer (`SinkMode::Cpu`); ek yedek: `glimagesink`/`xvimagesink` + `GstVideoOverlay` ile pencereye gömme. Tahta komut çıktısı istendi (PLAN §5). | M2, 🔧 |
| R5 | **Eski tahta donanımı** (VA-API yok/HEVC yok, zayıf CPU) | O | Y | `DecoderProbe` çalışma anında: VA H.264 → yazılım `avdec_h264`. HEVC yalnız donanımla çözülebiliyorsa sunulur. Yazılım çözümde alıcı WELCOME'da düşük `max` bildirir → gönderici 720p/30'a iner. | M2, `vainfo` çıktısı |
| R6 | **GStreamer 1.22 VA → GL sıfır kopya** (DMABuf/DRM modifier desteği 1.24'te olgunlaştı) | O | O | 1.22'de `vah264dec` çıktısı sistem belleğine indirilip `glupload` ile yüklenir (1080p60 ≈ 180 MB/s, Intel iGPU'da kabul edilebilir). Ölçümle doğrula; gerekirse `vapostproc` + DMABuf denenir veya `vaapisink` yedeği. | M2/M7 ölçüm |
| R7 | **TCP head-of-line + Wi-Fi dalgalanması** gecikme biriktirir | Y | Y | Sınırlı gönderim kuyruğu, birikmede bekleyenleri atıp IDR, bit hızına göre `SO_SNDBUF` (≈100 ms), AIMD bit hızı, alıcı raporuna dayalı 300 ms gecikme bütçesi (yol boşaltma), alıcıda çözücü önü birikme atma, ayrı CONTROL/AUDIO bağlantıları. Hedef tutmazsa **S5: UDP/RTP + FEC**. | ✅ M4 CI: 2,5 Mbps'lik yolda 3 Mbps akış, gecikme < 800 ms; M7 soak |
| R8 | **Tahta paneli giriş gecikmesi** (TV-tipi paneller 30–50 ms) ≤80 ms hedefini zorlar | O | O | Yazılım yolu ayrıca ölçülür ve raporlanır; README'de panel "oyun modu" önerisi. Kabul kriteri yazılım yolu + panel ayrımıyla değerlendirilir. | M7 |
| R9 | **Cihaz özgü MediaCodec hataları** (kalıcı Surface, `KEY_LATENCY`/`KEY_PRIORITY` yok sayma, döndürmede yeniden yapılandırma çökmesi, düşük seviye üst sınırları) | Y | O | Tüm anahtarlar yetenek sorgusuna bağlı; `CodecException` → yeniden yapılandırma → bir alt ön ayar → mesaj. Kalıcı Surface yerine `setSurface` yedeği. Sorunlu cihazlar için `quirks` tablosu. | 🔧 farklı üreticiler |
| R10 | **Ses yakalama kısıtları**: uygulama `allowAudioPlaybackCapture=false`, `USAGE_VOICE_COMMUNICATION` vb. yakalanamaz; DRM içerik | Y | D | Kullanıcıya açıklama ("Bu uygulama sesini paylaşmaya izin vermiyor; tahtaya Bluetooth/kablo hoparlör bağlayın"). Video etkilenmez. | M5 |
| R11 | **Opus kodlayıcı cihazda yok/bozuk** | D | D | Çalışma anında `MediaCodecList` sorgusu → AAC-LC yedeği; alıcı ikisini de çözer. | M5 |
| R12 | **PIN modunda aktif MITM** (6 hane çevrimdışı kaba kuvvet) | D | O | QR (parmak izi pinli) birincil; PIN tek kullanımlık, kısa ömürlü; ilk PIN eşleştirmesinden sonra parmak izi pinlenir. Kalıcı çözüm **S6: PAKE (CPace)**. | Güvenlik incelemesi |
| R13 | **Öğretmenin kurulum yetkisi yok** (.deb için sudo) | O | Y | taşınabilir `tar.gz` sürüm + `install-user.sh` (kullanıcı dizininden çalışır, yönetici yetkisi gerekmez); uzun vadede Pardus deposu/Yazılım Merkezi + ETAP merkezi yönetim dağıtımı (S7). | M8, kullanıcı cevabı |
| R14 | **Döndürmede < 1 sn hedefi**: kodlayıcı yeniden başlatma bazı cihazlarda 300–800 ms | O | O | Kalıcı Surface ile VirtualDisplay korunur; yeni CONFIG+IDR hemen gönderilir; alıcı `h264parse` çözünürlük değişimini kesintisiz işler. Alternatif: tek kare boyutunda (kare tuval) kodlayıp letterbox — yeniden başlatmasız, kalite kaybıyla. | M4 ölçüm |
| R15 | **Bellek sızıntısı / uzun oturum** (ByteBuffer, GstBuffer, QSslSocket tamponları) | O | O | Havuzlu tamponlar, ASan/LSan CI derlemesi (alıcı), Android Studio profiler + `tools/soak` RSS izleme. | M7 |
| R16 | **Çoklu ağ arayüzü / hotspot'ta IP değişimi** | O | D | Arayüz izleme ile QR otomatik yenileme; sunucu `0.0.0.0`'a bağlı. | M6 |
| R17 | **AGP/Kotlin/Compose ve Qt 6.4 API farkları** (Qt 6.4'te bazı QML/Network API'leri yok) | O | D | Qt sürümü 6.4.2'ye sabit CI (debian:12); daha yeni API'lerden kaçınılır. Android araç sürümleri version catalog'da sabit. | M0 CI |
| R18 | **Gizli bilgi sızması** (keystore, şifre) | D | Y | `.gitignore`, `gitleaks` CI adımı, imzalama yalnız GitHub Secrets (base64 keystore → çalışma anında dosyaya, iş sonunda silinir). | M0/M8 |
| R19 | **Lisans**: scrcpy (Apache-2.0) vb. kaynaklardan kod alınırsa atıf | D | O | Doğrudan kopyalanan her parça `NOTICE`'a ve dosya başlığına; bağımlılık lisans envanteri (`docs/THIRD_PARTY.md`). libqrencode LGPL dinamik bağlanır. | M8 |
| R20 | **Okul Wi-Fi'ı 2,4 GHz / kalabalık** — 1080p60 16 Mbps sürdürülemez | Y | O | AIMD otomatik düşer; "Zayıf ağ" ön ayarı; bağlantı kalitesi göstergesi öğretmeni bilgilendirir. | M4 netem, 🔧 |

## Açık sorular (kullanıcıdan)

1. ETAP 23 tahtada görüntü sunucusu, CPU, `vainfo` çıktısı (PLAN §5 komutu).
2. Test tabletleri (model, Android sürümü) — özellikle Android 15 QPR1+ ve 17 cihaz var mı?
3. Öğretmenlerin tahtaya paket kurma yetkisi.
