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
base64 -i yansit-release.jks | gh secret set YANSIT_KEYSTORE_BASE64 --repo ozkanceng/tablet-mirror
gh secret set YANSIT_KEYSTORE_PASSWORD --repo ozkanceng/tablet-mirror
gh secret set YANSIT_KEY_ALIAS --repo ozkanceng/tablet-mirror --body yansit
gh secret set YANSIT_KEY_PASSWORD --repo ozkanceng/tablet-mirror
```

(`gh secret set` şifreyi gizli olarak sorar; komut geçmişine yazılmaz.)

| Secret | İçerik |
|---|---|
| `YANSIT_KEYSTORE_BASE64` | `.jks` dosyasının base64 hali |
| `YANSIT_KEYSTORE_PASSWORD` | keystore şifresi |
| `YANSIT_KEY_ALIAS` | anahtar adı (`yansit`) |
| `YANSIT_KEY_PASSWORD` | anahtar şifresi |

`release.yml` keystore'u çalıştırıcının geçici dizinine yazar ve iş sonunda siler.

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
   git tag -a vX.Y.Z -m "Yansıt X.Y.Z"
   git push origin vX.Y.Z
   ```
3. `Release` iş akışı önce uçtan uca testleri (`integration.yml`) koşar; geçerse imzalı APK (`yansit-X.Y.Z.apk`), `.deb`, taşınabilir paket (`yansit-receiver-X.Y.Z-x86_64.tar.gz`, temiz Debian 12'de denenir) ve `SHA256SUMS` dosyalarını GitHub Releases'a yükler. Etiket `-` içeriyorsa (ör. `v0.1.0-rc1`) ön sürüm olarak işaretlenir.

Android `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH`; `.deb` sürümü etiketle aynıdır (`-rc1` → `~rc1`).
