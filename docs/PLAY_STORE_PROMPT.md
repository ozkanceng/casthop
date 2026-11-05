# CastHop — Google Play yayını için görev istemi (prompt)

Bu dosya yeni bir Claude Code oturumuna verilir: «`docs/PLAY_STORE_PROMPT.md` dosyasını oku ve uygula.» Aşağıdaki «Görev» bölümü istemin kendisidir; üstteki «Sahibine notlar» yalnız sana (proje sahibine) yöneliktir.

## Sahibine notlar (oturuma verme)

Yapay zekânın **yapamayacağı**, senin yapman gerekenler:

1. **Google Play geliştirici hesabı**: 25 USD tek seferlik ücret, kimlik doğrulama, sözleşmenin kabulü, şifre ve iki adımlı doğrulama. Hesap türü önemli:
   - **Kişisel hesap** (13 Kasım 2023'ten sonra açıldıysa): üretime çıkmadan önce **en az 12 test kullanıcısıyla 14 gün kesintisiz kapalı test** şart ([Google'ın yardım sayfası](https://support.google.com/googleplay/android-developer/answer/14151465)). Test kullanıcılarını sen bulursun (Gmail adresleri).
   - **Kuruluş hesabı**: bu şart yok, ama D-U-N-S numarası ister (alması birkaç hafta sürebilir).
2. Mağazada **herkese açık görünecek iletişim bilgileri**: e-posta (zorunlu), isteğe bağlı telefon ve adres. Hangisini yazacağına sen karar ver.
3. İlk sürümü **Play Console'dan yüklemek** gerekir; Play Developer API ancak uygulama oluşturulup ilk yükleme yapıldıktan sonra çalışır ([Runway belgesi](https://docs.runway.team/integrations/app-stores/google-play-console), üçüncü taraf; Google'ın kendi belgesinden doğrulanmadı). Oturum Console'u tarayıcıda senin açık oturumunla sürer; şifre girmez, sen girersin.
4. **Ön plan hizmeti beyanı için video**: Android 14+ `mediaProjection` ve `camera` hizmet türleri için Console, gerçek kullanımı gösteren bir video bağlantısı ister. Tabletten kaydı oturum alabilir; **YouTube'a «liste dışı» yüklemeyi sen yaparsın** (veya oturumun izin verdiği bir yöntemi seçersin).
5. **Veri güvenliği formu, içerik derecelendirme ve hedef kitle** hukuki beyandır. Oturum kodu okuyup taslağı hazırlar; **göndermeden önce sen onaylarsın**.
6. **Üretime yayınlama** ve her geri alınamaz adım için oturum sana sorar.
7. Karar bekleyen iki konu (aşağıda D1, D2).

Sırrı sohbete yapıştırma. İmza anahtarı ve parolalar yalnız GitHub Secrets'ta kalır.

---

## Görev (istem)

Sen CastHop'un Google Play yayınını hazırlayıp yürüten mühendissin. Depo: `/Volumes/KIOXIA/projeler/tahta` (GitHub: `ozkanceng/casthop`, herkese açık). Önce `CLAUDE.md` ve `../CLAUDE.md` dosyalarını oku; oradaki kurallar bu görevde de geçerli. Yanıtların Türkçe, kısa (caveman) olsun; kod, kimlik ve yorumlar İngilizce.

### Hedef

Android uygulamasını (`com.sorumatik.casthop`) Google Play'de **Türkçe (varsayılan) ve İngilizce** listelemeyle yayınlamak. Kapsam: tablet/telefon uygulaması. TV uygulaması (`io.github.ozkanceng.casthop.tv`) ayrı bir liste olur; bu görevde yalnız Faz 6'daki sorudur.

### Değişmez kurallar

- **Gizli bilgi asla commit edilmez, sohbete yazılmaz, loglanmaz**: keystore, parola, token, servis hesabı JSON'u, `pair_key`. İmzalama yalnız GitHub Actions'ta, Secrets ile (`YANSIT_KEYSTORE_PASSWORD`, `YANSIT_KEY_ALIAS`, `YANSIT_KEY_PASSWORD` ve keystore Secret'ı). `release.yml` içindeki Secret adlarını değiştirme.
- Dokunma: `*.jks`, `*.keystore`, `*.pem`, `*.key`, `keystore.properties`, `signing.properties`, `local.properties`, `.env*`, `protocol/vectors/vectors.json`, `gradle/libs.versions.toml` mevcut sürümleri, `gradle-wrapper.properties`, `core`, `CLAUDE.md`. `brand-logo-adaylari/` izlenmeyen klasördür; commit etme.
- Hesap açma, ücret ödeme, sözleşme/şartları kabul etme, CAPTCHA, şifre veya doğrulama kodu girme: **yapma**, kullanıcıya bırak. Console'da bir form gönderirken, bir sürümü «incelemeye gönder / yayınla» derken ve geri alınamaz her adımda önce kullanıcıdan açık onay al; onay oturum ve eylem başınadır.
- Commit yalnız kullanıcı isterse. Biçim: İngilizce, ≤ 72 karakter, sonuna `Co-Authored-By: ...` satırı. Rebase, amend, force-push yok.
- «Cihazda denedim» deme; gerçek cihaz gerektiren denetimleri `docs/TESTING.md` listesine ekle. Çalıştırmadığını «geçti» diye bildirme.
- Mağaza metni Google Play Meta Veri politikasına uysun: anahtar kelime doldurma yok, rakip marka adı yok, «en iyi/№1» iddiası yok, emoji yok, sahte yorum yok.

### Bilinen gerçekler (doğrula, tahmin etme)

| | |
|---|---|
| Uygulama kimliği | `com.sorumatik.casthop` (hata ayıklama sürümü `.debug` ekli, Play'e gitmez). Kotlin paketi ve `namespace` `io.github.ozkanceng.yansit` kalır |
| Sürüm | etiketten gelir (`-PyansitVersionName`); `versionCode = MMmmpp` (0.3.2 → 302). Her Play yüklemesi benzersiz ve artan `versionCode` ister, yani yeni etiket gerekir |
| SDK | minSdk 29 (Android 10), targetSdk 37 |
| İzinler | `INTERNET` (yalnız yerel ağ yuvaları), `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK`, `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_CAMERA`; hizmet türü `mediaProjection|camera` |
| Üçüncü taraf SDK | Analitik, reklam, çökme raporu SDK'sı yok (`libs.versions.toml` ve `build.gradle.kts` dosyalarını yeniden tara) |
| Veri | Eşleşmiş ekran adı, adresi ve anahtarı yalnız cihazda (`allowBackup=false`); görüntü/ses/kamera yalnız kullanıcının seçtiği alıcıya, yerel ağda, TLS 1.3 ile gider; geliştiriciye hiçbir şey gitmez |
| İmza | Yalnız GitHub Actions'ta, etiket `v*` itilince. Bu Mac'te keystore yok |
| CI | `release.yml` yalnız `assembleRelease` çalıştırır; **AAB üretmiyor** |
| Mağaza metinleri | `play/listings/tr-TR/` ve `play/listings/en-US/` altında `title.txt` (≤30), `short_description.txt` (≤80), `full_description.txt` (≤4000). Karakter sayıları ölçüldü, sınırların içinde. Metni değiştirirsen sayıyı yeniden ölç |
| Siteler | `https://ozkanceng.github.io/casthop/` (TR/EN, `site/`), kaynak `https://github.com/ozkanceng/casthop` |
| Marka | Logo ve simgeler `brand/` altında; `brand/build.sh`, `brand/render.swift` |

### Faz 1 — Depo işleri (tek başına yap)

1. **AAB üretimi.** `android/app/build.gradle.kts` imzalama ayarını oku; `release.yml`'e `./gradlew :app:bundleRelease -PyansitVersionName="$VERSION"` ekle, çıktıyı **workflow artifact** olarak yükle (herkese açık sürüm varlığı yapma). Yerelde `./gradlew :app:bundleRelease` imzasız derlenir mi dene; imzalı çıktı yalnız CI'da olur, bunu raporla.
2. **Gizlilik politikası sayfası.** `site/privacy.html` (TR/EN, `site/index.html` ile aynı dil seçimi ve görünüm), `index.html` altbilgisinden bağlantı. İçerik yalnız doğru olanı söylesin: toplanan veri yok, analitik/reklam yok, izinlerin amacı (ekran yakalama, kamera, mikrofon, yerel ağ, bildirim), verinin yalnız kullanıcının seçtiği yerel ağdaki alıcıya TLS ile gittiği, cihazda tutulan eşleşme verisi, iletişim için GitHub sorun sayfası ve Faz 0'da verilen e-posta (verilmediyse GitHub). Yayın tarihi ve sürüm yaz. Playwright ile masaüstü + mobil + koyu tema + iki dil kontrol et.
3. **Görseller** (`play/graphics/` altına; ham kaynak ve üretim betiği ile):
   - Uygulama simgesi 512×512 PNG (32 bit, ≤1024 KB), `brand/casthop-icon.svg` kaynaklı.
   - Öne çıkan grafik 1024×500 PNG, TR ve EN (metin dile göre).
   - Ekran görüntüleri: telefon (en az 2, 16:9 veya 9:16), 7 inç ve 10 inç tablet (en az 1'er), TR ve EN. Tabletten (`adb`) gerçek ekran alınır. **Görüntüde QR kod, PIN, bağlantı kodu, kişisel ekran adı, token veya Wi-Fi adı çıkmasın**; çıkarsa o kareyi kullanma ve kopyasını sil. İngilizce görüntüler için uygulama dilini İngilizceye çevir (`adb shell cmd locale set-app-locales` veya sistem dili).
4. `CHANGELOG.md` → `[Yayımlanmamış / Unreleased]`, `docs/RELEASING.md` (Play bölümü), `docs/TESTING.md` (gerçek cihaz maddeleri), `README*` (Play bağlantısı, yayınlanınca) güncelle.
5. Doğrulama: `cd android && ./gradlew lint testDebugUnitTest`; `site/**` için Playwright. Sonucu olduğu gibi bildir.

### Faz 2 — Play Console (tarayıcıda, kullanıcının açık oturumuyla)

Kullanıcıya «Console'da oturum açıp haber ver» de; şifre isteme. Sonra:

1. **Uygulama oluştur**: ad `CastHop: Ekran Yansıtma`, varsayılan dil **Türkçe (tr-TR)**, tür «Uygulama», «Ücretsiz». Beyanları kullanıcıya göster, o kabul eder.
2. **Mağaza girdisi**: `play/listings/tr-TR/*` metinlerini yapıştır; **İngilizce (en-US) çeviri** ekle (`play/listings/en-US/*`). Simge, öne çıkan grafik, ekran görüntüleri (her dil için ayrı). Kategori önerisi: **Araçlar** (ekran yansıtma aramaları burada); etiketler: ekran yansıtma / screen mirroring türü. Sonucu raporla. İletişim e-postası, web sitesi (`https://ozkanceng.github.io/casthop/`), gizlilik politikası `.../privacy.html`.
3. **Uygulama içeriği beyanları** (taslağı hazırla, kullanıcı onaylasın):
   - Gizlilik politikası URL'si; reklamlar: **yok**; uygulama erişimi: giriş gerekmez, tüm işlevler açık.
   - Hedef kitle: **18 yaş ve üstü** (öğretmenler için araç; çocuklara yönelik değil → Aileler politikası devreye girmesin). Gerekçeyi rapora yaz.
   - İçerik derecelendirme anketi: şiddet/ticaret içeriği yok; kullanıcı üretimli içerik paylaşımı yok.
   - **Veri güvenliği**: kodu ve `docs/` dosyalarını tarayarak «Bilgi gerçekleri» tablosunu doğrula; toplanan/paylaşılan veri yok görünüyorsa bunu **kullanıcıya gerekçesiyle göster**, o onaylayınca işaretle. Ekran/ses/kamera verisinin kullanıcı seçtiği yerel alıcıya gitmesi nasıl sınıflandırılır, Google'ın tanımına bakarak yaz; emin değilsen kullanıcıya sor.
   - **Ön plan hizmeti (FGS) beyanı**: `mediaProjection` ve `camera`; açıklama + video bağlantısı (Faz 0'daki not).
   - Diğer beyanlar (devlet uygulaması, finans, sağlık, haber): hayır.
4. **Ülkeler**: tüm ülkeler (Türkiye dahil), fiyat ücretsiz.

### Faz 3 — Test ve üretim yolu

1. **Dahili test**: AAB'yi yükle (indirme: `gh run download` ile CI artifact; imzayı Secrets'taki anahtar atar). Play Uygulama İmzalama'ya kayıt: bkz. karar D1.
2. **Kişisel hesapsa kapalı test**: kullanıcının verdiği ≥12 e-posta listesiyle kapalı test kanalı, katılım bağlantısı; kullanıcıya «testçilere şu bağlantıyı gönder, 14 gün kalsınlar» de. Takvimi (bitiş tarihi) rapora yaz. Üretim erişimi başvurusu 14. günden sonra: Console'daki sorulara dürüst yanıt taslağı hazırla, kullanıcı onaylasın.
3. Kuruluş hesabıysa doğrudan üretim kanalı taslağı hazırlanır.
4. **Sürüm notları** TR/EN (≤500 karakter) `CHANGELOG.md`'den.
5. **Üretime yayınla düğmesine basmadan önce kullanıcıya sor.**

### Faz 4 — Ret ve geri bildirim

Google ret verirse e-postadaki gerekçeyi kullanıcıdan al, `play/` ve kodda gereken düzeltmeyi yap, yeni `versionCode` için yeni etiket öner (etiketi **sen atma**, kullanıcı karar versin).

### Faz 5 — İsteğe bağlı otomasyon (ilk yükleme ve onaylardan sonra)

Kullanıcı isterse `release.yml`'e Play'e yükleme adımı ekle (ör. `r0adkll/upload-google-play`, sürüm sabitli, yalnız dahili/kapalı kanala). Servis hesabı JSON'u **yalnız** `PLAY_SERVICE_ACCOUNT_JSON` GitHub Secret'ı olur. Kullanıcıya adım adım anlat: Google Cloud'da servis hesabı + anahtar → Play Console'da «Kullanıcılar ve izinler»de bu e-postaya uygulama düzeyinde sürüm yayınlama izni → JSON'u Secret'a yapıştırma → yerel kopyayı silme. Yeni bağımlılığı (Action) `docs/THIRD_PARTY.md`'ye yaz.

### Faz 6 — TV listesi (yalnız soru)

TV uygulamasını ayrı listeyle yayınlama için gereken ek işleri (Leanback bildirimi, afiş 1280×720, TV ekran görüntüleri, TV kalite denetimi) listele ve kullanıcıya «şimdi mi, sonra mı?» diye sor. Başlama.

### Kararlar (kullanıcıdan al, varsayma)

- **D1 — İmza anahtarı.** (a) *Önerilen:* Play'in ürettiği uygulama imzalama anahtarı; bizim anahtar yükleme anahtarı olur. Sonuç: Play'den kurulan ve GitHub'dan kurulan APK'lar **farklı imzalı** olur; kullanıcı kaynak değiştirirse önce kaldırıp yeniden kurmalı. Bunu `README` ve mağaza «Bilmeniz gerekenler» bölümüne yaz. (b) Mevcut anahtarı Play'e şifreli yüklemek: imza aynı kalır ama anahtar dosyası senin elinde olmalı ve yüklemeyi sen yapmalısın. Oturum anahtarı görmez.
- **D2 — Varsayılan dil ve kategori**: önerilenler Türkçe ve Araçlar; kullanıcı değiştirirse metin dosyalarını ve girdiyi uyarla.

### Bitirme

Sonunda tek listede ver: yapılan ve doğrulanan adımlar, değişen dosyalar, çalıştırılan komutlar ve sonuçları (geçti/kaldı), yapılamayanlar ve nedeni (özellikle Console'daki onay bekleyenler), kullanıcıdan beklenenler (hesap, testçiler, video, onaylar), varsayımlar. Hiçbir sırrı rapora yazma.
