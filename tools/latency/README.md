# tools/latency — uçtan uca gecikme ölçümü

İki yöntem vardır; ikisi de tabletteki **Ayarlar → Gecikme ölçümü** ekranını kullanır.

## 1. Otomatik (test deseni)

Tablet ekranın üst bandına her karede o anın zamanını (tabletin `CLOCK_MONOTONIC` saati, µs) ikili kod olarak
çizer. Tahta çözülen karede bu kodu okur, PING/PONG ile bilinen saat ofsetini ekler ve kare **ekrana verildiği
anda** farkı hesaplar:

```
uçtan uca = (tahtada çizim anı) − (tablette çizim anı + saat ofseti)
```

Bu süre tablet bileşicisi + ekran yakalama + kodlama + ağ + çözme + çizimi kapsar; yalnız tahta panelinin kendi giriş
gecikmesi (TV tipi panellerde 10–50 ms, RISKS R8) dışarıda kalır. Sonuç tahtada yansıtma bilgi çubuğunda
(`uçtan uca … ms, p95 … ms`) ve alıcı istatistik dosyasında (`e2e_avg_ms`, `e2e_p95_ms`) görünür.

### Desen biçimi

Karenin üst %10'luk bandı genişlik boyunca 64 eşit hücreye bölünür; beyaz hücre = 1, siyah = 0.

| Hücre | İçerik |
|---|---|
| 0–3 | başlangıç koruması `1010` |
| 4–51 | zaman damgası, 48 bit, µs, en anlamlı bit önce |
| 52–59 | denetim: damganın 6 baytının XOR'u |
| 60–63 | bitiş koruması `0101` |

Çözücü her hücrenin ortasındaki parlaklığı (Y) okur, eşik 128. Korumalar veya denetim tutmazsa kare yok sayılır
(desen ekranda değilken ölçüm yapılmaz). Ortak test vektörü: `0x0123456789AB` →
`1010 000000010010001101000101011001111000100110101011 00100010 0101`.

## 2. Elle (fotoğraf)

Desen ekranında büyük bir milisaniye saati de vardır. Tableti tahtanın önüne koyup **ikisini aynı fotoğrafta**
çekin; tabletteki ve tahtadaki saat arasındaki fark uçtan uca gecikmedir (panel gecikmesi dahil). En az 10 fotoğrafın
ortancasını alın.
