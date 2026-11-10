# Değişiklik Günlüğü / Changelog

Biçim [Keep a Changelog](https://keepachangelog.com/tr-TR/1.1.0/) esaslıdır; sürümleme [SemVer](https://semver.org/lang/tr/) izler.

## [Yayımlanmamış / Unreleased]

## [0.3.10] - 2026-11-10

### Düzeltildi / Fixed
- **Durağan slaytta kalite kendiliğinden düşmez (Android ve iPhone/iPad):** Ekran değişmezken saniyede 2 küçük kare gider. Bunlardan birinin biraz geç gelmesi ağ tıkanıklığı sanılıyor, veri hızı yarım dakikada birkaç kez düşüyor, kalite Zayıf ağ'a inip ancak bir dakika sonra dönüyordu. Artık az kare taşıyan ölçümler tıkanıklık sayılmaz; slayt değişince görüntü tam kaliteyle gelir.
- **Samsung tabletin kendiliğinden yeniden başlaması giderildi (Android):** Ağ yavaşlayıp kalite bir basamak düştüğünde yeni görüntü kodlayıcısı eski, daha büyük bir kare alabiliyordu. Samsung'un Exynos kodlayıcı sürücüsü bu durumda tableti çökertiyordu. Ekran artık kodlayıcıya OpenGL üzerinden çizilir ve her kare tam kodlayıcının boyutunda olur; başka boyutta kare ona ulaşamaz.

### Değiştirildi / Changed
- **H.265 varsayılan, daha az veri (Android):** Tablette ve ekranda donanım H.265 desteği varsa yansıtma H.265 ile yapılır ve aynı görüntü için üçte bir daha az veri gönderilir (Dengeli: 8 yerine 5,2 Mbit/s başlangıç). Kalabalık Wi-Fi'da donma azalır. Desteklemeyen tahtada H.264 sürer; Ayarlar'dan kapatılabilir.
- **Döndürmede görüntü kesilmez (Android):** Tablet döndürülünce kodlayıcı yeniden kurulmuyor; görüntü aynı akışın içinde yeni yönüne geçer, tahtada kısa siyah ya da donma olmaz. Dikey tutulan tabletin görüntüsü yatay tahtada ortada, iki yanı siyah gösterilir (bu sürümün tahta, TV ve Apple alıcılarında dikey görüntü aşağıdaki gibi daha büyük ve keskin gelir).
- **Dikey tablet tahtada daha keskin (Android gönderici; Pardus, Windows, Android TV ve Apple alıcıları):** Dikey ekran yatay görüntüye sığdırılırken karenin yalnız %40'ı kullanılıyordu. Tablet artık resmi 90° çevirip görüntünün tamamını kullanır, tahta geri çevirerek dikey gösterir: aynı veri hızıyla yazı daha net. Eski alıcıya bağlanınca önceki gibi kenar boşluklu gönderilir.
- **Durağan ekranda ağ boşalır (Android):** Ekran değişmezken kodlayıcı aynı kareyi saniyede 10 kez yolluyordu; kalabalık 2,4 GHz ağı boşuna dolduruyordu. Artık saniyede 2 küçük kare gider (≈ 3 kbit/s). Tahta yine her zaman güncel görüntüyü gösterir.
- **Ağ tıkanınca daha az donma (Android):** Ağın alamayacağı kare artık hiç kodlanmıyor; önceden kodlanıp atılan kare sonraki tam kareye kadar görüntüyü donduruyordu. Yavaş ağ denemesinde tam kare yeniden gönderimi 4'ten 1'e indi.
- **Küçük tahtada daha az veri, yumuşak başlangıç (Android):** Veri hızı çözünürlüğe ve kare hızına göre ayarlanır: 1280×720 tahtaya büyük tahtanın veri hızı gönderilmez. Yansıtma 30 kare/sn ile başlar, ağ 3 sn rahat taşıyınca 60'a çıkar; bağlantının ilk anında donma olmaz.
- **Çözünürlük yansıtma boyunca sabit (Android):** Ağ zayıflayınca çözünürlük artık düşmüyor; yalnız veri hızı ve kare hızı (60 → 30) düşer. Görüntü kesilmez, yazı aynı netlikte kalır. Çözünürlük Ayarlar'daki kaliteye göre seçilir; tahtada hatırlanan düşük kalite yalnız veri hızını ve kare hızını etkiler.

### Eklendi / Added
- **2,4 GHz uyarısı (Android):** Kalite ağ yüzünden kendiliğinden düştüğünde tablet 2,4 GHz Wi-Fi'daysa uygulama 5 GHz bir ağ ya da telefonun 5 GHz hotspot'unu önerir. Kalabalık sınıflarda donmaların asıl nedeni 2,4 GHz kanalının dolmasıdır.

## [0.3.9] - 2026-11-10

### Değiştirildi / Changed
- **Varsayılan görüntü kalitesi Dengeli (Android ve iPhone/iPad):** Kalabalık okul Wi-Fi'ında En iyi kalite sık donuyordu. Ayarlar'dan kaliteyi daha önce kendisi seçmiş olanların seçimi korunur. En iyi kalite Ayarlar'dan seçilebilir.

## [0.3.8] - 2026-11-10

### Düzeltildi / Fixed
- **Samsung tabletin kendiliğinden yeniden başlaması (Android):** En iyi kalitede 4K alıcıya bağlanınca uygulama 2560×1600 görüntü istiyordu; Samsung Exynos kodlayıcı sürücüsü bu boyutu kurarken çöktü ve tablet yeniden başladı. Görüntü genişliği gelişmiş ayarlar dahil en çok 1920 piksel oldu; tahtada fark görülmez, ağ yükü de azalır.
- **Yansıtma biterken uygulamanın kapanması (Android):** Bağlantı yeniden kurulurken yansıtma durdurulursa uygulama hata verip kapanabiliyordu.

## [0.3.7] - 2026-11-10

### Değiştirildi / Changed
- **Yavaş ağda daha kısa donma (Android ve iPhone/iPad):** Ağ görüntüyü taşıyamazsa kalite artık 8 yerine 3 saniyede bir basamak iner; iki düşüş arasındaki bekleme 15 yerine 5 saniye. Kalite yükselişi eskisi gibi temkinli.
- **Her tahta için son kalite hatırlanır:** Bir tahtada kalite Dengeli ya da Zayıf ağ'a inmişse sonraki yansıtma oradan başlar, başta donma olmaz. Ağ iyiyse kalite dakikada bir basamak yükselerek seçilen ayara döner. Gelişmiş ayarlarda elle değer verilmişse kalite değişmez.

## [0.3.6] - 2026-11-10

### Eklendi / Added
- Yansıtma sırasındaki ekrana **Ayarlar** düğmesi eklendi (Android ve iPhone/iPad). Yansıtma sürerken ayarlar açılırsa değişikliklerin bir sonraki yansıtmada geçerli olacağı yazılır.

## [0.3.5] - 2026-11-10

### Değiştirildi / Changed
- **Ses aktarımı varsayılan olarak kapalı:** Android ve iPhone/iPad'de yalnız görüntü aktarılır. Ses, Ayarlar → "Sesi ekrana aktar" ile açılır. Android ilk açılışta ses iznini artık sormuyor; izin, ayar açılınca istenir. Ayarı daha önce kendisi açmış ya da kapatmış olanların seçimi korunur.

## [0.3.4] - 2026-11-10

### Eklendi / Added
- iPhone ve iPad göndericisi App Store Connect'e yüklendi (`CastHop: Ekran Yansıtma`); mağaza metinleri (Türkçe ve İngilizce) ve ekran görüntüleri `apple/store/` altında.

### Düzeltildi / Fixed
- Daha önce Yansıt kurulmuş tahtalarda menüde hâlâ "Yansıt Alıcı" görünüyordu ve bu kısayol eski programı (ör. 0.1.2) açıyordu. Menü kaydının adı `io.github.ozkanceng.casthop.receiver` oldu. `.deb` kurulurken tahtadaki kullanıcıların eski taşınabilir kurulumu (`~/.local/opt/yansit`) ve menü kaydı kendiliğinden silinir; çalışıyorsa eski program kapatılır; açık olan otomatik başlatma yeni programa yönlendirilir, kapatılmışsa kapalı kalır. CastHop da ilk açılışta aynı kayıtları denetler.

### Değiştirildi / Changed
- **iOS göndericisinin kimlikleri değişti:** uygulama `com.sorumatik.casthop`, yayın uzantısı `com.sorumatik.casthop.broadcast`, App Group `group.com.sorumatik.casthop`. Uygulama henüz yayımlanmadığından eşleşme kaybı yoktur.

## [0.3.3] - 2026-11-05

### Eklendi / Added
- Kurulum sitesi artık İngilizce de konuşuyor: dil tarayıcı diline göre seçilir, sayfanın üstündeki düğmeyle değiştirilir ve seçim hatırlanır. Tek komutlu `kur` betiğinin mesajları da sistem diline göre Türkçe veya İngilizce çıkar.
- Gizlilik politikası sayfası (`/privacy.html`, Türkçe ve İngilizce); Google Play için gerekli.
- Sürüm iş akışı Google Play için imzalı bir AAB üretiyor; herkese açık sürüme konmaz, yalnız iş akışı çıktısı olarak 30 gün saklanır.

### Değiştirildi / Changed
- **Tablet uygulamasının paket adı `com.sorumatik.casthop` oldu** (Google Play kimliği kalıcıdır). Kotlin paketi değişmedi. Eski paket adıyla (`io.github.ozkanceng.yansit`) kurulu tabletler bu sürüme güncellenmez: yeni uygulama ayrı kurulur, eskisini silebilirsiniz; eşleştirmeler yeniden yapılır. Protokol değişmedi, eski ve yeni sürümler aynı alıcılarla çalışır.

## [0.3.2] - 2026-11-05

### Düzeltildi / Fixed
- Android TV alıcısı ağda görünmüyordu: yayın adı TV'nin kendi adıyla aynı olunca Android yayını hiç tamamlamıyordu. Yayın adına artık `(CastHop)` ekleniyor; listelerde TV'nin adı değişmeden görünüyor.

## [0.3.1] - 2026-11-05

### Değiştirildi / Changed
- Yeni CastHop logosu: Android, TV, Apple, Pardus ve Windows uygulamalarının simgeleri ile site ve README güncellendi; site artık sekmede simge gösteriyor. Android bildirimi küçük boyuta uygun ayrı bir simge kullanıyor.
- Tek komutlu kurulum indirme sırasında tek satırda dosya adı ve yüzde gösteriyor; geçici klasör yolu ve sağlama dosyası satırı görünmüyor.

## [0.3.0] - 2026-11-05

### Eklendi / Added
- "Nasıl bağlanırım?" rehberinde CastHop'un Pardus tahtaya, TV'ye, Android cihaza, Windows'a ve Mac'e nasıl kurulduğu açılır bölümlerle anlatılıyor; indirme bağlantıları, Pardus için yönetici şifresiz tek komutlu kurulum ve kullanım sorumluluğu uyarısı da var.
- iPhone ve iPad'de kamerayı yansıtma: bağlanma ekranındaki "Kamerayı yansıt" düğmesi ReplayKit olmadan uygulama içinden gönderir; uygulamadan çıkınca durur.
- Mac alıcısı `casthop-mac.dmg` olarak indirilebiliyor.
- Kamerayı yansıtma: ana ekrandaki anahtarla ekran yerine kamera gönderilir; kitap, defter ve belge göstermek için. Önizleme tablette görünür, ön ve arka kamera arasında geçilir.
- Ağda bulma listesi alıcının türünü (tahta, TV, bilgisayar, tablet) simgeyle gösteriyor.
- Android TV alıcısı: televizyon, Android tahta veya tablet artık CastHop ile yansıtma alabiliyor. QR veya kodla bağlanılır; kumandayla da dokunmayla da kullanılır. Ses, eşleşme ve ağda bulma tahtadaki gibi çalışıyor.
- Windows alıcısı: `casthop-setup-x86_64.exe` kurucusu Qt ve GStreamer'ı içinde getiriyor, güvenlik duvarı kuralını ekliyor ve Başlat menüsüne giriyor. Görüntü varsa Direct3D 11 donanım çözücüsüyle çözülüyor, işlemciyle çiziliyor. Ağda bulma `DnsServiceRegister` ile çalışıyor; anahtar dosyası yalnız geçerli kullanıcıya açık.
- Apple desteği: Mac, Apple TV ve iPad alıcı olarak çalışıyor; iPhone ve iPad ekranı ReplayKit yayınıyla gönderiliyor. Aynı protokol kullanılıyor, Android ve tahta ile birlikte çalışıyor. Gerçek cihaz denetimi henüz yapılmadı (`docs/TESTING.md`).
- Ağ uzun süre yetersizse tablet çözünürlüğü ve kare hızını kendiliğinden düşürüyor, ağ düzelince geri yükseltiyor; öğretmen Ayarlar'a girmek zorunda değil.

### Düzeltildi / Fixed
- Yansıtma başında görüntünün siyah kalıp ancak duraklatıp devam edince gelmesi için Android'de ekran, kodlayıcı çalışmaya başladıktan sonra bağlanıyor.
- Tahtada GL siyah çizince CPU çizime geçiş artık kalıcı: sonraki oturumlar siyah başlamıyor. Görüntü ölçülemezse denetim bırakılmıyor, yineleniyor.
- Tablette "Yansıtmayı Durdur"a basınca uygulama çöküyordu; aynı hata duraklatma ve devam düğmelerinde de olabilirdi.
- `kur` komutuyla kurulan tahtada "Tahta açılınca başlat" uygulamayı açamıyordu; eski kayıt da güncelleniyor.

### Değiştirildi / Changed
- Android ve iOS uygulamalarının metinleri artık yalnız tahtadan değil, her alıcıdan söz ediyor ("Ekrana Bağlan").
- Ürünün yeni adı **CastHop**. Paket, indirme dosyaları ve menü adı değişti; `.deb` eski `yansit-receiver` paketinin yerini alıyor. Eşleşmeler, ayarlar ve tablet uygulaması güncellemesi korunuyor; eski tablet ve yeni tahta birlikte çalışıyor.
- Tıkalı ağda anahtar kareler üst üste binmiyor; önceki kare yazılırken gelen istekler tek isteğe indiriliyor.
- Daha düşük gecikme: yansıtma sırasında Wi-Fi güç tasarrufu kapatılıyor, tahtadaki donanım çözücü kare bekletmiyor, periyodik anahtar kare aralığı 2 sn'den 10 sn'ye çıktı.
- Kısa bir Wi-Fi takılmasından sonra bit hızı önceki değerinin %85'ine yaklaşık 17 sn yerine 7 sn içinde dönüyor; görüntü daha çabuk netleşiyor.

## [0.1.2] - 2026-11-04

### Düzeltildi / Fixed
- Alıcıda görüntü çizilmezken yansıtma sürüyor görünüyordu. GL eklentisi yoksa kareler artık boşa gitmiyor, işlemciyle çiziliyor.
- Yansıtma başlayınca görüntü denetleniyor: GL siyah çiziyorsa işlemci çizimine, donanım çözücü siyah veriyorsa yazılım çözücüye kendiliğinden geçiliyor.

### Eklendi / Added
- Ayarlar → Uyumlu görüntü modu: görüntüyü işlemciyle çizer.
- Tanı günlüğü: `~/.cache/yansit/receiver.log` (Qt, GStreamer, GL sürücüsü, çözücü, görüntü denetimi).
- Uçtan uca testler ekran pikselini ölçüyor: kare sayısı tek başına görüntüyü kanıtlamıyordu.
- Kurulum sayfası (GitHub Pages): tahtada `.deb` ile kurulum, şifresiz kurulum için `site/kur`, tablet için QR ve doğrudan indirme.
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
