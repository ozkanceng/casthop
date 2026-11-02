# protocol/

Yansıt protokolünün ([docs/PROTOCOL.md](../docs/PROTOCOL.md)) makine-okur test vektörleri.
Kotlin (`android/protocol`) ve C++ (`receiver/src/protocol`) uygulamaları aynı vektörleri test eder.

Machine-readable test vectors for the Yansıt protocol. Both implementations test against them.

- `vectors/*.hex` — ham baytlar (boşluk/yorum serbest, `#` satır sonuna kadar yorum)
- `vectors/vectors.json` — her vektörün beklenen ayrıştırma sonucu veya hata kodu
