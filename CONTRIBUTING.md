# Katkı Rehberi / Contributing

Katkılarınız için teşekkürler! / Thanks for contributing!

## Başlamadan önce / Before you start

- Mimari: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), protokol: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
- Büyük değişiklikler için önce bir issue açıp tartışın. / Open an issue first for large changes.
- Davranış kuralları: [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Geliştirme ortamı / Development setup

### Android gönderici / Android sender
- JDK 17+ (Android Studio'nun JBR'si yeterli), Android SDK Platform 37 + Build-Tools 37.
```bash
cd android
./gradlew lint testDebugUnitTest assembleDebug
```

### Pardus alıcı / Pardus receiver
Debian 12 / Pardus 23 üzerinde:
```bash
sudo apt install build-essential cmake ninja-build pkg-config \
  qt6-base-dev qt6-declarative-dev libqt6opengl6-dev \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libgstreamer-plugins-bad1.0-dev \
  libssl-dev libqrencode-dev libavahi-client-dev libgtest-dev
cmake -S receiver -B receiver/build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build receiver/build
ctest --test-dir receiver/build --output-on-failure
```
Başka bir işletim sistemindeyseniz `debian:12` konteyneri kullanın (CI ile aynı ortam).

## Kurallar / Rules

- **Gizli bilgi commit etmeyin** (keystore, şifre, token). CI `gitleaks` ile tarar.
- Protokolü değiştiren her PR `docs/PROTOCOL.md` ve `protocol/vectors/` dosyalarını birlikte günceller; Kotlin ve C++ uygulamaları aynı vektörleri geçmelidir.
- Commit mesajları: kısa özet satırı (≤ 72 karakter), gerekirse boş satır + açıklama. Örnek: `receiver: add VA-API decoder probe`.
- Yeni kullanıcı metinleri hem Türkçe hem İngilizce eklenir.
- Kod stili: Kotlin resmi stil; C++ `.clang-format` (receiver/).
- Başka projeden kod kopyalarsanız lisansına uyun, dosya başlığında ve `NOTICE` içinde atıf yapın.
