# Sürüm Yayımlama ve APK İmzalama

## 1. İmzalama anahtarını bir kez oluşturun (kendi bilgisayarınızda)

```bash
keytool -genkeypair -v -keystore yansit-release.jks -alias yansit \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Yansit, O=Yansit, C=TR"
```

- Sorulan **keystore şifresi** ve **anahtar şifresi**ni bir parola yöneticisine kaydedin.
- `yansit-release.jks` dosyasını depoya **asla** eklemeyin (`.gitignore` `*.jks`'yi zaten dışlar). Güvenli bir yerde yedekleyin: anahtar kaybolursa aynı uygulamaya güncelleme yayımlanamaz.

## 2. GitHub Secrets'a ekleyin

Depo → **Settings → Secrets and variables → Actions → New repository secret**, ya da `gh` ile:

```bash
base64 -i yansit-release.jks | gh secret set YANSIT_KEYSTORE_BASE64 --repo ozkanceng/casthop
gh secret set YANSIT_KEYSTORE_PASSWORD --repo ozkanceng/casthop
gh secret set YANSIT_KEY_ALIAS --repo ozkanceng/casthop --body yansit
gh secret set YANSIT_KEY_PASSWORD --repo ozkanceng/casthop
```

(`gh secret set` şifreyi gizli olarak sorar; komut geçmişine yazılmaz.)

| Secret | İçerik |
|---|---|
| `YANSIT_KEYSTORE_BASE64` | `.jks` dosyasının base64 hali |
| `YANSIT_KEYSTORE_PASSWORD` | keystore şifresi |
| `YANSIT_KEY_ALIAS` | anahtar adı (`yansit`) |
| `YANSIT_KEY_PASSWORD` | anahtar şifresi |

`release.yml` keystore'u çalıştırıcının geçici dizinine yazar ve iş sonunda siler.

## 2a. Android TV uygulaması

Tablet uygulamasıyla aynı Secret'ları kullanır, ek ayar gerekmez. Sürümde `casthop-tv.apk` çıkar. Google Play'de TV için yayımlamak ayrı başvuru ister: Play Console'da uygulamayı oluşturup "Android TV" biçimini ekleyin.

## 2a-bis. Google Play (tablet uygulaması)

- Play kimliği **`com.sorumatik.casthop`** (kalıcı). Mağaza metinleri `play/listings/`, görev istemi `docs/PLAY_STORE_PROMPT.md`.
- Her `v*` etiketi, aynı Secret'larla imzalı bir **AAB** üretir: Actions çıktısı `play-aab` (`casthop-<sürüm>.aab`, 30 gün). Genel sürüm varlıklarına konmaz.
- Play'e yükleme Play Console'dan yapılır; her yükleme yeni `versionCode` ister, yani yeni etiket. Uygulama imzalama anahtarını Play yönetir, bizim anahtar yükleme anahtarıdır; bu yüzden Play'den ve GitHub'dan kurulan APK'lar farklı imzalıdır (kaynak değiştirirken önce kaldırın).
- Gizlilik politikası: `https://ozkanceng.github.io/casthop/privacy.html` (`site/privacy.html`).

## 2b. Windows imzası (isteğe bağlı)

İmzasız kurucu çalışır, ama Windows SmartScreen uyarı gösterir. Kod imzalama sertifikası alırsanız `.pfx` dosyasını depoya eklemeyin; GitHub Secrets'a koyun:

```bash
base64 -i casthop-codesign.pfx | gh secret set CASTHOP_WINDOWS_PFX_BASE64 --repo ozkanceng/casthop
gh secret set CASTHOP_WINDOWS_PFX_PASSWORD --repo ozkanceng/casthop
```

İmzalama adımı `windows.yml`'e sertifika alındığında eklenir.

## 2c. Apple

iPhone, iPad, Apple TV ve Mac dağıtımı için ücretli Apple Developer Program üyeliği gerekir. Sertifika ve API anahtarları da yalnız GitHub Secrets'ta durur:

```bash
base64 -i casthop-distribution.p12 | gh secret set CASTHOP_APPLE_CERT_BASE64 --repo ozkanceng/casthop
gh secret set CASTHOP_APPLE_CERT_PASSWORD --repo ozkanceng/casthop
gh secret set CASTHOP_APPLE_TEAM_ID --repo ozkanceng/casthop
base64 -i AuthKey_XXXX.p8 | gh secret set CASTHOP_ASC_KEY_BASE64 --repo ozkanceng/casthop
gh secret set CASTHOP_ASC_KEY_ID --repo ozkanceng/casthop
gh secret set CASTHOP_ASC_ISSUER_ID --repo ozkanceng/casthop
```

iPhone ve iPad göndericisi (`SenderApp`) App Store'da `com.sorumatik.casthop` (uzantı `com.sorumatik.casthop.broadcast`, App Group `group.com.sorumatik.casthop`) kimliğiyle yayımlanır; kimlikler kalıcıdır. Yayın adımı `apple.yml`'e henüz eklenmedi; sürüm el ile yapılır:

1. Geliştirici portalında App ID'leri ve App Group'u kaydedin, App Group'u iki App ID'ye atayın.
2. `apple/` içinde `xcodegen generate`, sonra `xcodebuild archive -scheme SenderApp -destination generic/platform=iOS DEVELOPMENT_TEAM=<takım> CURRENT_PROJECT_VERSION=<yapı no> -allowProvisioningUpdates -authenticationKeyPath ...` ile arşivleyin.
3. `xcodebuild -exportArchive` ile (`method: app-store-connect`) dışa aktarın. API anahtarı `App Manager` rolündeyse bulut imzalama izni vermez; bu durumda Apple Distribution sertifikasını ve iki App Store profilini portaldan elle oluşturup `signingStyle: manual` kullanın.
4. IPA'yı `xcrun altool --upload-app` (olmazsa `iTMSTransporter -m upload`) ile `--apiKey/--apiIssuer` vererek yükleyin.

Sertifika, `.p8` anahtarı ve profiller repoya girmez; yerelde `~/.appstoreconnect` altında tutun. Mağaza metinleri ve ekran görüntüleri `apple/store/` altındadır.

Üyelik açılana dek `release.yml` Mac alıcısını `apple/ci/build-dmg.sh` ile geçici imzalı (`-`) `casthop-mac.dmg` olarak yayımlar. Secret gerekmez; Gatekeeper ilk açılışta uyarır. iPhone, iPad ve Apple TV sürümleri üyelik olmadan dağıtılamaz.

## 3. Yerelde imzalı derleme (isteğe bağlı)

`android/keystore.properties` (commit edilmez):

```properties
storeFile=/tam/yol/yansit-release.jks
storePassword=...
keyAlias=yansit
keyPassword=...
```

## 4. Sürüm yayımlama

1. `CHANGELOG.md`'de `[Yayımlanmamış]` bölümünü `[X.Y.Z] - YYYY-AA-GG` yapın, commit edin.
2. Etiketleyin ve gönderin:
   ```bash
   git tag -a vX.Y.Z -m "CastHop X.Y.Z"
   git push origin vX.Y.Z
   ```
3. `Release` iş akışı önce uçtan uca testleri (`integration.yml`) koşar; geçerse imzalı APK (`yansit-X.Y.Z.apk`), `.deb`, taşınabilir paket (`casthop-X.Y.Z-x86_64.tar.gz`, temiz Debian 12'de denenir) ve `SHA256SUMS` dosyalarını GitHub Releases'a yükler. Etiket `-` içeriyorsa (ör. `v0.1.0-rc1`) ön sürüm olarak işaretlenir.

Aynı dosyalar sürümden bağımsız adlarla da yüklenir (`casthop.apk`, `casthop_amd64.deb`, `casthop-x86_64.tar.gz`). Kurulum sayfası (`site/`, GitHub Pages) ve `site/kur` betiği `releases/latest/download/` üzerinden bunları kullanır; ön sürümler "latest" sayılmaz.

Android `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH`; `.deb` sürümü etiketle aynıdır (`-rc1` → `~rc1`).
