# android/ — Yansıt gönderici / sender

Kotlin + Jetpack Compose, minSdk 29 (Android 10), targetSdk 37 (Android 17).

| Modül | İçerik |
|---|---|
| `:app` | Uygulama: Compose arayüz, izinler, QR tarama (CameraX + zxing-cpp), `MirrorService` (ön plan servisi), `ScreenEncoder` (MediaCodec) |
| `:sender` | Saf Kotlin/JVM gönderici çekirdeği: `StreamPlanner` (ön ayar/yetenek → çözünürlük, fps, bit hızı), `SendQueue` (kare atmalı kuyruk), `MirrorSession` (oturum, PING, raporlar, bit hızı uyarlaması) |
| `:protocol` | Saf Kotlin/JVM protokol kütüphanesi ve istemci el sıkışmaları (Android bağımlılığı yok) |
| `:fake-sender` | `tools/fake-sender` — `:sender`'ı dosya kaynağıyla çalıştırıp CI'da gerçek alıcıya karşı test eder |

Akış: QR → `Reachability` (QR'daki adreslere paralel TCP) → `SenderClient.hello` (TLS 1.3, parmak izi sabitleme) →
tahta onayı (gerekirse) → sistem ekran paylaşımı onayı → `MirrorService` → `VirtualDisplay` → kalıcı giriş
Surface'ı → `ScreenEncoder` → `SendQueue` → VIDEO bağlantısı.

```bash
./gradlew lint test assembleDebug
./gradlew installDebug   # bağlı cihaza kur / install on a connected device
```
İmzalı sürüm: [docs/RELEASING.md](../docs/RELEASING.md).
