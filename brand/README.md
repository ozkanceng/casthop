# CastHop marka dosyaları

Logo: C harfi, ortasında tablet, ağzında tablet ile aynı renkte nokta. Görüntü tabletten çıkıp ekrana sıçrar.

| Dosya | Kullanım |
|---|---|
| `casthop-icon.svg` | Köşeleri yuvarlak uygulama simgesi, 48 px ve üstü |
| `casthop-icon-small.svg` | 32 px ve altı için kalın çizim |
| `casthop-icon-square.svg` | Köşeleri sistemin kestiği kare simge (iPhone, iPad) |
| `casthop-mark-mono.svg` | Tek renk |
| `casthop-logo.svg` | Simge ve yazı, açık zemin |
| `casthop-logo-on-dark.svg` | Simge ve yazı, koyu zemin |
| `apple-*.svg` | Mac simgesi, Apple TV katmanları ve raf görselleri |

Renkler: mavi `#2388F0` → `#083C96`, amber `#FFCB57` → `#FF9326`, küçük boyutta düz mavi `#0F5FC4` ve amber `#FFB23E`, yazı `#101820`.

CastHop yazısı bu projeye özel çizildi, yazı tipi dosyası gerekmez.

## Yeniden üretme

```bash
brand/build.sh
```

Betik macOS'ta çalışır (`swiftc`, ImageMagick). Apple PNG'lerini, Windows `.ico` dosyasını, alıcı SVG'sini ve site görsellerini yazar.

Android simgeleri elle yazılmış vektördür: `android/{app,tv}/src/main/res/drawable/`. Logo değişirse `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_notification.xml` ve `banner.xml` de güncellenir. Uyarlanabilir simgede işaret 66 dp güvenli alana sığar: 512 birimlik çizim 0,1634 ile küçültülür.
