# protocol/

Yansıt protokolünün ([docs/PROTOCOL.md](../docs/PROTOCOL.md)) makine-okur test vektörleri.
Kotlin (`android/protocol`) ve C++ (`receiver/src/protocol`) uygulamaları aynı vektörleri test eder.

Machine-readable test vectors for the Yansıt protocol. Both implementations test against them.

- `gen/schema.py`: mesaj/tag şeması, hata adları, HMAC etiketleri (PROTOCOL.md'nin makine-okur karşılığı)
- `gen/gen_vectors.py`: bağımsız Python referans üreteci; deterministik çıktı
- `vectors/vectors.json`: üretilen vektörler. Bölümler: `error_codes`, `frames`, `control`, `crypto`, `qr`.
  Her vektör ya beklenen alanları ya da `error` adını taşır.

Vektörleri yeniden üretmek / güncel olduğunu doğrulamak:

```bash
python3 -I protocol/gen/gen_vectors.py
python3 -I protocol/gen/gen_vectors.py --check
```

CI `--check` adımı JSON elle düzenlenirse veya şema değişip JSON yenilenmezse başarısız olur.
