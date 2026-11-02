# android/ — Yansıt gönderici / sender

Kotlin + Jetpack Compose, minSdk 29 (Android 10), targetSdk 37 (Android 17).

| Modül | İçerik |
|---|---|
| `:app` | Uygulama (UI, yansıtma servisi, kodlama, ağ) |
| `:protocol` | Saf Kotlin/JVM protokol kütüphanesi (Android bağımlılığı yok) |

```bash
./gradlew lint test assembleDebug
./gradlew installDebug   # bağlı cihaza kur / install on a connected device
```
İmzalı sürüm: [docs/RELEASING.md](../docs/RELEASING.md).
