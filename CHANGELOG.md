# Değişiklik Günlüğü / Changelog

Biçim [Keep a Changelog](https://keepachangelog.com/tr-TR/1.1.0/) esaslıdır; sürümleme [SemVer](https://semver.org/lang/tr/) izler.

## [Yayımlanmamış / Unreleased]

### Eklendi / Added
- Proje planı, mimari, protokol v1 taslağı ve risk analizi (`docs/`).
- Monorepo iskeleti: `android/` (Kotlin + Compose kabuğu, saf-JVM `:protocol` modülü), `receiver/` (C++20 + Qt6 QML kabuğu; tahtanın paylaşılabilir ağ adreslerini listeler).
- CI: Android lint/test/APK, alıcı Debian 12 derleme + test (normal ve ASan/UBSan), `.deb` paketi, gizli bilgi taraması; etiketle otomatik sürüm yayımlama.
- `docs/RELEASING.md`: APK imzalama (GitHub Secrets) ve sürüm yayımlama rehberi.
