# tools/

| Araç | Durum | Açıklama |
|---|---|---|
| `fake-sender` | ✅ M2 | H.264 Annex-B dosyasını protokol üzerinden gerçek zaman hızında yollar. `--engine mirror`: Android uygulamasının gönderici çekirdeğiyle (`android/sender`) |
| `integration` | ✅ M2 | Sahte gönderici ↔ alıcı uçtan uca senaryoları (CI: `integration.yml`) |
| `conformance` | ✅ | Herhangi bir alıcıyı `fake-sender` ile dener: `tools/conformance/run.py --help`. Yeni alıcılar bunu geçmeli (`docs/PROTOCOL.md` §11). |
| `fake-receiver` | ✅ M3 | Ayrı araç gerekmedi: `casthop --headless` (Linux veya `receiver/ci/Dockerfile`) bir dizüstünde tahtasız alıcıdır; bkz. TESTING.md M3 |
| `latency` | ✅ M7 | Zaman damgalı test deseni (tablette Ayarlar → Gecikme ölçümü) + alıcıda otomatik ölçüm; bkz. [latency/README.md](latency/README.md) |
| `soak` | ✅ M7 | Uzun süre testi: gecikme eğimi, RSS, kayıp → CSV + SVG (CI: `soak.yml`, her gece 30 dk) |

## fake-sender

```bash
cd android && ./gradlew :fake-sender:installDist
../tools/fake-sender/build/install/fake-sender/bin/fake-sender --help
```

Örnek: tahtadaki QR içeriğiyle 60 fps akış

```bash
fake-sender --uri 'yansit://c?v=1&h=192.168.1.20&p=47800&t=…&f=…&n=Tahta' --input test.h264 --fps 60
```

## integration

Debian 12'de (veya `receiver/ci/Dockerfile` imajında) alıcı derlendikten sonra:

```bash
tools/integration/install-deps.sh
YANSIT_IT_GUI=1 tools/integration/run.sh receiver/build/src/casthop \
    tools/fake-sender/build/install/fake-sender/bin/fake-sender it-work
```

Senaryolar ve kabul ölçütleri `run.sh` başında ve `check.py` içinde.

## soak

Debian 12'de (veya `yansit-deb12` imajında), **ASan'sız** alıcıyla:

```bash
tools/integration/install-deps.sh
tools/soak/run.sh receiver/build/src/casthop tools/fake-sender/build/install/fake-sender/bin/fake-sender 30 soak-work
```

`soak-work/summary.json` kabul ölçütleri: dakikalık ortanca gecikmenin eğimi ≤ 0,5 ms/dk, alıcı belleği (RSS) artışı
< %5, alıcıda kare kaybı yok, göndericinin bilinçli attığı kareler ≤ %0,5, geç ses ≤ %1. Grafik: `soak-work/soak.svg`.
