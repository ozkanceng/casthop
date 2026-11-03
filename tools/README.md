# tools/

| Araç | Durum | Açıklama |
|---|---|---|
| `fake-sender` | ✅ M2 | H.264 Annex-B dosyasını protokol üzerinden gerçek zaman hızında yollar. `--engine mirror`: Android uygulamasının gönderici çekirdeğiyle (`android/sender`) |
| `integration` | ✅ M2 | Sahte gönderici ↔ alıcı uçtan uca senaryoları (CI: `integration.yml`) |
| `fake-receiver` | ✅ M3 | Ayrı araç gerekmedi: `yansit-receiver --headless` (Linux veya `receiver/ci/Dockerfile`) bir dizüstünde tahtasız alıcıdır; bkz. TESTING.md M3 |
| `latency` | M7 | Zaman damgalı test deseni + ölçüm |
| `soak` | M7 | 30 dk uzun süre testi |

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
YANSIT_IT_GUI=1 tools/integration/run.sh receiver/build/src/yansit-receiver \
    tools/fake-sender/build/install/fake-sender/bin/fake-sender it-work
```

Senaryolar ve kabul ölçütleri `run.sh` başında ve `check.py` içinde.
