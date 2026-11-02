# Yansıt

**Android tabletin ekranını ve sesini Pardus ETAP akıllı tahtaya kablosuz yansıtın.**
Kurulumu kolay, açık kaynak, düşük gecikmeli.

[English](README.en.md) · [Plan](docs/PLAN.md) · [Mimari](docs/ARCHITECTURE.md) · [Protokol](docs/PROTOCOL.md)

> ⚠️ **Geliştirme aşamasında.** Henüz kullanıma hazır sürüm yok. İlerleme: [CHANGELOG](CHANGELOG.md).

## Ne işe yarar?

Öğretmen tabletindeki her şeyi — ders uygulamaları, videolar, fotoğraflar, el yazısı notlar — sınıftaki akıllı tahtada gösterir:

1. Tahtada **Yansıt Alıcı**'yı açın; ekranda büyük bir QR kod görünür.
2. Tablette **Yansıt**'ı açıp **"Tahtaya Bağlan"**'a dokunun ve QR kodu okutun.
3. Ekran paylaşım iznini onaylayın. Bu kadar.

Hedefler: 1080p / 60 FPS, uçtan uca ≤ 80 ms gecikme (5 GHz Wi-Fi), ses dahil, şifreli bağlantı (TLS 1.3), tahtada onay olmadan kimse yansıtamaz.

## Bileşenler

| Dizin | İçerik |
|---|---|
| [`android/`](android/) | Tablet uygulaması (Kotlin, Jetpack Compose), Android 10+ |
| [`receiver/`](receiver/) | Pardus masaüstü alıcısı (C++20, Qt 6, GStreamer), Pardus ETAP 23 / Debian 12 |
| [`protocol/`](protocol/) | Protokol test vektörleri |
| [`tools/`](tools/) | Sahte gönderici/alıcı, gecikme ölçümü, uzun süre testi |
| [`web-receiver/`](web-receiver/) | (Sonraki aşama) kurulumsuz tarayıcı alıcısı |
| [`docs/`](docs/) | Plan, mimari, protokol, riskler, test listeleri |

## Kurulum

Henüz yayımlanmış sürüm yok. İlk sürümle birlikte burada öğretmenler için adım adım kurulum anlatılacak.

## Sorun giderme

**Tablet tahtayı bulamıyor / bağlanamıyor.** Birçok okul ağı, aynı Wi-Fi'daki cihazların birbirini görmesini engeller (*istemci izolasyonu*). Çözüm: tabletin **kişisel hotspot**'unu açın ve tahtayı bu ağa bağlayın; tahtadaki QR kod yeni adresle otomatik güncellenir.

## Geliştirme

Bkz. [CONTRIBUTING.md](CONTRIBUTING.md).

## Lisans

[Apache-2.0](LICENSE). Üçüncü taraf bileşenler: [NOTICE](NOTICE).
