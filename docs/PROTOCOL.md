# CastHop Protokolü — Sürüm 1

Durum: **v1** — M1'de `protocol/vectors/vectors.json` test vektörleriyle donduruldu; Kotlin ve C++ uygulamaları bu vektörlere bayt bayt uyar. Anahtar sözcükler (MUST/SHOULD/MAY) RFC 2119 anlamındadır.

## 1. Kurallar

- Tüm tamsayılar **işaretsiz, big-endian** (ağ bayt sırası).
- `uN` = N bitlik işaretsiz tamsayı, `i64` = 64 bit iki tümleyenli işaretli tamsayı. `bytes[N]` = sabit N bayt. `str` = UTF-8, sonlandırıcısız.
- `*_us` alanları gönderen tarafın **kendi monotonik saati**nin mikrosaniyesidir (Android: `CLOCK_MONOTONIC` = `System.nanoTime()/1000`; Linux: `CLOCK_MONOTONIC`). Saatler arası ilişki §7.4'teki PING/PONG ile kurulur.
- S = gönderici (tablet), R = alıcı (tahta).

## 2. Taşıma

| Öğe | Değer |
|---|---|
| Transport | TCP, varsayılan port **47800** (R dinler) |
| Güvenlik | TLS **1.3 zorunlu** (1.2 ve altı reddedilir), ALPN `yansit/1` |
| R sertifikası | İlk çalıştırmada üretilen kendinden imzalı ECDSA P-256, 10 yıl geçerli. **Parmak izi** = `SHA-256(DER sertifika)` (32 bayt) |
| S sertifika doğrulaması | CA zinciri kullanılmaz; parmak izi QR'daki veya kayıtlı eşleştirmedeki değerle **MUST** eşleşmeli. PIN akışında ilk bağlantıda TOFU. |
| Soket ayarları | `TCP_NODELAY=1` (iki taraf), S'de `SO_SNDBUF≈256 KiB`, keepalive kapalı (uygulama düzeyi PING var) |

Bir oturum üç TCP bağlantısından oluşur, hepsi aynı porta:

| Rol | Yön | İçerik |
|---|---|---|
| CONTROL | çift yönlü | yalnız CONTROL çerçeveleri |
| VIDEO | S→R (ilk ATTACH yanıtı hariç) | VIDEO çerçeveleri |
| AUDIO | S→R (ilk ATTACH yanıtı hariç) | AUDIO çerçeveleri (ses kapalıysa açılmaz) |

Her bağlantıda TLS kurulduktan hemen sonra R ilk mesaj olarak **CHALLENGE** gönderir (§7.1). Bağlantının rolü S'nin ilk mesajıyla belirlenir: `HELLO` veya `RESUME` → CONTROL; `ATTACH` → VIDEO/AUDIO.

## 3. Çerçeve formatı

Her mesaj = 24 baytlık başlık + yük.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-------------------------------+---------------+---------------+
|        magic = 0x5954 ("YT")  |  version = 1  |     type      |   0
+-------------------------------+---------------+---------------+
|            flags              |          reserved = 0         |   4
+-------------------------------+-------------------------------+
|                                                               |   8
+                     pts_us (u64)                              +
|                                                               |  12
+---------------------------------------------------------------+
|                     seq (u32)                                 |  16
+---------------------------------------------------------------+
|                     length (u32) — yük bayt sayısı            |  20
+---------------------------------------------------------------+
|                     payload [length]                          |  24
```

| Ofset | Alan | Tip | Açıklama |
|---|---|---|---|
| 0 | magic | u16 | `0x5954`. Farklıysa bağlantı **MUST** kapatılır. |
| 2 | version | u8 | Çerçeve formatı sürümü, `1`. Desteklenmeyen → kapat. |
| 3 | type | u8 | `0x01` VIDEO, `0x02` AUDIO, `0x03` CONTROL. Diğerleri → kapat. |
| 4 | flags | u16 | Bkz. tablo. Tanımsız bitler gönderende 0 **MUST**, alanda yok sayılır **MUST**. |
| 6 | reserved | u16 | Gönderende 0, alanda yok sayılır. |
| 8 | pts_us | u64 | VIDEO/AUDIO: yakalama anı (S saati). CONTROL: gönderim anı. |
| 16 | seq | u32 | Bağlantı başına 0'dan artan, taşmada 0'a döner. Boşluk = atlanmış çerçeve. |
| 20 | length | u32 | Yük uzunluğu. Üst sınır: VIDEO 8 MiB, AUDIO 64 KiB, CONTROL 64 KiB. Aşılırsa → kapat. |

**Bayraklar**

| Bit | Ad | Anlam |
|---|---|---|
| 0 (`0x0001`) | CONFIG | Yük codec yapılandırmasıdır (§5, §6). |
| 1 (`0x0002`) | KEYFRAME | Bağımsız çözülebilir kare (IDR/IRAP). |
| 2 (`0x0004`) | DISCONTINUITY | Bu çerçeveden önce S tarafında çerçeve atıldı veya akış yeniden yapılandırıldı. |
| 3–15 | — | Ayrılmış. |

## 4. Bağlantı başına durum makinesi

```
R: TLS kabul → CHALLENGE gönder → [10 sn içinde ilk mesaj bekle]
   HELLO   → kimlik doğrula → (meşgul? PENDING_APPROVAL → onay) → WELCOME | REJECT
   RESUME  → oturum ≤10 sn önce koptu mu, kanıt doğru mu → WELCOME | REJECT
   ATTACH  → oturum var mı, kanıt doğru mu → ATTACH_RESULT
   diğer   → REJECT(MALFORMED) + kapat
```

CONTROL bağlantısında 5 sn hiçbir çerçeve gelmezse bağlantı ölü sayılır. R, CONTROL kopunca oturumu **10 sn** saklar (RESUME penceresi); VIDEO/AUDIO bağlantıları kapatılır.

## 5. VIDEO yükü

- **CONFIG** çerçevesi: `codec u8` + codec yapılandırması (Annex-B, başlangıç kodlu). H.264: SPS+PPS. H.265: VPS+SPS+PPS. Akış başında ve her yeniden yapılandırmada (döndürme, çözünürlük/codec değişimi) **MUST** gönderilir; ardından gelen ilk kare KEYFRAME **MUST** olmalı.
- Diğer çerçeveler: tek bir erişim birimi (access unit), Annex-B, `codec` baytı **yok**. KEYFRAME çerçeveleri ayrıca SPS/PPS içerebilir (`KEY_PREPEND_HEADER_TO_SYNC_FRAMES`).
- B-kare kullanılmaz (`pts` artan sırada). Alıcı çözme sırası = varış sırası.

## 6. AUDIO yükü

- **CONFIG**: `codec u8` + yapılandırma. Opus: RFC 7845 `OpusHead` (19 bayt). AAC-LC: `AudioSpecificConfig` (ISO 14496-3).
- Diğer: tek codec paketi (Opus: 20 ms, ham paket; AAC: ham erişim birimi, ADTS **yok**).

**Codec kimlikleri**

| ID | Codec |
|---|---|
| `0x01` | H.264 / AVC |
| `0x02` | H.265 / HEVC |
| `0x10` | Opus |
| `0x11` | AAC-LC |

## 7. CONTROL yükü

```
+----------+-----------+-------------------------------+
| msg_type | msg_flags |  TLV TLV TLV ... (yük sonuna) |
|   u8     |    u8     |                               |
+----------+-----------+-------------------------------+

TLV:  tag u8 | len u16 | value[len]
```

- `msg_flags` bit0 = **CRITICAL**: alıcı bu `msg_type`'ı tanımıyorsa `BYE(MALFORMED)` ile kapatmalı. Bit0=0 ve tanınmayan tip → yok sayılır (ileri uyumluluk). Bağlantının ilk mesajı bu kuralın dışındadır: HELLO, RESUME veya ATTACH değilse `REJECT(MALFORMED)` ile kapatılır (§4).
- Bilinmeyen `tag` → yok sayılır **MUST**. Aynı `tag` iki kez → MALFORMED. Zorunlu `tag` eksik → MALFORMED. Sabit uzunluklu tipte `len` uyuşmazlığı → MALFORMED.
- Değer tipleri: `u8/u16/u32/u64/i64` (len 1/2/4/8/8), `bool` (len 1; 0 = yanlış, diğer her değer doğru; gönderen yalnız 0/1 yazar), `bytes[N]`, `str` (en fazla 255 bayt, geçerli UTF-8; aşırı uzun kodlama, vekil kod noktası ve U+10FFFF üstü geçersiz), `list<u8>` (len = eleman sayısı), `pairs` (`codec u8, flags u8` çiftleri; len çift **MUST**).
- Gönderen TLV'leri **artan tag sırasıyla** yazar (kanonik kodlama; test vektörleri bu sırayı kullanır). Alıcı sıraya bağımlı olmamalıdır.
- `len` değeri yük sonunu aşarsa → MALFORMED.
- Aşağıda **Z** = zorunlu, **O** = opsiyonel.

### 7.1 Kimlik doğrulama ve oturum

#### `0x00` CHALLENGE (R→S, her bağlantının ilk mesajı)
| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | min_version | u16 | Z |
| 0x02 | max_version | u16 | Z |
| 0x03 | server_nonce | bytes[16] | Z — her bağlantıda CSPRNG ile yeni |
| 0x04 | receiver_name | str | O |

#### `0x01` HELLO (S→R)
| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | version | u16 | Z — seçilen protokol sürümü (`1`) |
| 0x02 | device_name | str | Z — örn. "Ayşe Öğretmen – Galaxy Tab S9" |
| 0x03 | device_id | bytes[16] | Z — kurulumda üretilen kalıcı rastgele kimlik |
| 0x04 | app_version | str | Z |
| 0x05 | auth_method | u8 | Z — 1=QR token, 2=PIN, 3=eşleştirme anahtarı |
| 0x06 | client_nonce | bytes[16] | Z |
| 0x07 | auth_proof | bytes[32] | Z — §7.1.1 |
| 0x08 | key_id | bytes[16] | O — auth_method=3 ise oturum katmanı **MUST** ister (yoksa REJECT(AUTH_FAILED)) |
| 0x09 | video_codecs | list<u8> | Z — S'nin donanımla kodlayabildikleri, tercih sırasıyla |
| 0x0A | audio_codecs | list<u8> | O |
| 0x0B | max_width | u16 | Z — kodlayıcı üst sınırı |
| 0x0C | max_height | u16 | Z |
| 0x0D | max_fps | u8 | Z |
| 0x0E | request_pairing | bool | O — doğruysa WELCOME pair_key içerir |

##### 7.1.1 Kimlik kanıtı

```
auth_proof = HMAC-SHA256(
    key  = secret,
    msg  = "yansit-auth-v1"            (14 bayt ASCII)
         || auth_method                (u8)
         || server_nonce               (16)
         || client_nonce               (16)
         || receiver_cert_fp           (32, S'nin TLS'te GÖRDÜĞÜ sertifikanın SHA-256'sı)
         || device_id                  (16) )
```

| auth_method | secret |
|---|---|
| 1 QR token | QR'daki 16 baytlık token |
| 2 PIN | 6 hanelik PIN'in ASCII baytları (örn. `"042917"`) |
| 3 Eşleştirme | `pair_key` (32 bayt), `key_id` ile seçilir |

R, kendi sertifika parmak iziyle aynı hesabı yapar ve **sabit-zamanlı** karşılaştırır. Araya giren bir saldırgan farklı sertifika sunacağından kanıt tutmaz.

Token/PIN kuralları: token ve PIN **tek kullanımlıktır**; başarılı HELLO sonrası yenilenir. PIN için 5 başarısız denemede PIN yenilenir ve 30 sn kilit. R genelinde 10 sn'de en fazla 10 HELLO.

#### `0x02` WELCOME (R→S)
| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | version | u16 | Z |
| 0x02 | receiver_name | str | Z |
| 0x03 | session_id | bytes[16] | Z |
| 0x04 | display_width | u16 | Z — piksel |
| 0x05 | display_height | u16 | Z |
| 0x06 | display_refresh | u8 | Z — Hz |
| 0x07 | video_decoders | pairs | Z — `flags` bit0=donanım, bit1=tercih edilen |
| 0x08 | audio_decoders | pairs | Z |
| 0x09 | attach_key | bytes[32] | Z — bu oturumun ATTACH anahtarı |
| 0x0A | resume_token | bytes[32] | Z — her WELCOME'da yenilenir |
| 0x0B | pair_key | bytes[32] | O — request_pairing=1 ve auth başarılıysa |
| 0x0C | key_id | bytes[16] | O — pair_key varsa R **MUST** ekler |
| 0x0D | audio_enabled | bool | Z — yanlışsa S AUDIO bağlantısı açmaz |

#### `0x03` REJECT (R→S, ardından R bağlantıyı kapatır)
| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | reason | u8 | Z |
| 0x02 | retry_after_ms | u32 | O |

`reason`: 1 VERSION_UNSUPPORTED, 2 AUTH_FAILED, 3 BUSY_DENIED, 4 BUSY_TIMEOUT, 5 RATE_LIMITED, 6 MALFORMED, 7 INTERNAL, 8 SESSION_UNKNOWN, 9 LOCKED_OUT.

#### `0x04` PENDING_APPROVAL (R→S)
Başka bir gönderici aktif; tahtada devralma diyalogu gösteriliyor. `0x01 timeout_ms u32` (Z, varsayılan 20000). Onay → eski oturuma `BYE(TAKEOVER)`, yeniye `WELCOME`. Ret/zaman aşımı → `REJECT(3|4)`. Aynı `device_id` + `auth_method=3` ile gelen gönderici kendi eski oturumunu onaysız devralır.

#### `0x50` ATTACH (S→R, VIDEO/AUDIO bağlantısının ilk mesajı)
| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | session_id | bytes[16] | Z |
| 0x02 | stream_kind | u8 | Z — 1=VIDEO, 2=AUDIO |
| 0x03 | proof | bytes[32] | Z |

`proof = HMAC-SHA256(attach_key, "yansit-attach-v1" || session_id || stream_kind || server_nonce)` (`server_nonce` = bu bağlantının CHALLENGE'ı).

#### `0x51` ATTACH_RESULT (R→S)
`0x01 status u8` (Z): 0=OK, aksi REJECT `reason` kodları. OK değilse R kapatır. OK sonrası bağlantıda yalnız S→R medya çerçeveleri akar.

#### `0x60` RESUME (S→R, yeni CONTROL bağlantısında HELLO yerine)
| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | session_id | bytes[16] | Z |
| 0x02 | proof | bytes[32] | Z |

`proof = HMAC-SHA256(resume_token, "yansit-resume-v1" || session_id || server_nonce)`. Başarı → yeni `WELCOME` (aynı session_id, yeni resume_token/attach_key); S ardından VIDEO/AUDIO'yu yeniden ATTACH eder ve CONFIG + KEYFRAME ile başlar.

#### `0x70` BYE (iki yön)
`0x01 reason u8` (Z): 1 USER_STOP, 2 PROJECTION_STOPPED, 3 TAKEOVER, 4 RECEIVER_CLOSING, 5 ERROR, 6 TIMEOUT, 7 MALFORMED. Gönderen ardından tüm oturum bağlantılarını kapatır.

### 7.2 Akış denetimi

#### `0x10` STREAM_CONFIG (S→R, bilgilendirme)
Yeni yapılandırmanın duyurusu; yetkili kaynak medya bağlantısındaki CONFIG çerçevesidir. Video için 0x03–0x06 ve 0x09, ses için 0x06–0x08 gönderilir.

| Tag | Ad | Tip | Z/O |
|---|---|---|---|
| 0x01 | stream_kind | u8 | Z — 1=VIDEO, 2=AUDIO |
| 0x02 | codec | u8 | Z |
| 0x03 | width | u16 | O |
| 0x04 | height | u16 | O |
| 0x05 | fps | u8 | O |
| 0x06 | bitrate_bps | u32 | O |
| 0x07 | sample_rate | u32 | O |
| 0x08 | channels | u8 | O |
| 0x09 | rotation_deg | u16 | O |

#### `0x11` KEYFRAME_REQUEST (R→S)
`0x01 reason u8` (Z): 1 DECODER_RESET, 2 BACKLOG_DROPPED, 3 DECODE_ERROR, 4 USER. S en geç bir kare süresi içinde IDR üretmeli; 200 ms'den sık istekler birleştirilir. Önceki IDR hâlâ yazılıyorsa S isteği bekletebilir ve yazım bitince tek IDR üretir; bekletme 1 sn'yi geçmez. Böylece tıkalı ağda IDR'ler üst üste binmez.

#### `0x40` PAUSE / `0x41` UNPAUSE (S→R)
PAUSE: S kodlayıcıya yeni kare beslemeyi durdurur (`VirtualDisplay.setSurface(null)`), R "Ders duraklatıldı" ekranını gösterir. UNPAUSE sonrası ilk video çerçevesi KEYFRAME **MUST**. Tag yok.

### 7.3 Durum raporları

#### `0x30` RECEIVER_REPORT (R→S, 500 ms'de bir)
Tüm alanlar Z. Aralıkta hiç kare gelmediyse gecikme alanları 0'dır.

| Tag | Ad | Tip | Açıklama |
|---|---|---|---|
| 0x01 | interval_ms | u16 | Raporun kapsadığı süre |
| 0x02 | frames_received | u32 | Aralıkta alınan video çerçevesi |
| 0x03 | frames_rendered | u32 | Çizilen |
| 0x04 | frames_dropped | u32 | R'de atılan (geç/kuyruk) |
| 0x05 | bytes_received | u32 | |
| 0x06 | c2r_avg_us | u32 | Yakalama→alım ort.: `recv_R − (pts_S + offset)` |
| 0x07 | c2r_min_us | u32 | |
| 0x08 | c2r_max_us | u32 | |
| 0x09 | c2d_avg_us | u32 | Yakalama→çizim ort. |
| 0x0A | decode_backlog | u16 | Çözücü önünde bekleyen çerçeve |
| 0x0B | audio_late | u16 | Geç gelip atlanan ses paketi |

#### `0x31` SENDER_STATS (S→R, 1 sn'de bir; tahta üst bilgisi için)
`0x01 bitrate_bps u32` (Z), `0x02 fps_x100 u16` (Z), `0x03 frames_dropped u32` (Z), `0x04 encoder_name str` (O).

### 7.4 Saat senkronu

#### `0x20` PING (S→R, 1 sn'de bir; R de MAY gönderir)
`0x01 ping_id u32` (Z), `0x02 t0_us u64` (Z, gönderenin saati).

#### `0x21` PONG
`0x01 ping_id u32`, `0x02 t0_us u64` (yankı), `0x03 t1_us u64` (alış, yanıtlayanın saati), `0x04 t2_us u64` (gönderiş, yanıtlayanın saati) — hepsi Z. `t3` = PONG'un yerel alış anı.

```
offset = ((t1 − t0) + (t2 − t3)) / 2     # yanıtlayan_saat − başlatan_saat
rtt    = (t3 − t0) − (t2 − t1)
```
Son 8 örnekten en düşük `rtt`'li örneğin `offset`'i kullanılır. S her PING'e son hesapladığı ofseti `0x03 offset_us i64` (O) ekleyebilir. Değer `R_saati − S_saati`'dir: R, S'nin damgasına bu değeri ekleyerek kendi saatine çevirir. Alan isteğe bağlıdır, ama R ofseti öğrenene kadar ses paketlerini atar; ses isteyen S alanı göndermelidir.

### 7.5 Zaman aşımları (özet)

| Olay | Süre |
|---|---|
| TLS sonrası ilk mesaj | 10 sn |
| CONTROL sessizliği → ölü | 5 sn |
| RESUME penceresi | 10 sn |
| S yeniden bağlanma denemesi | 250 ms'den başlayıp 1 sn'ye üstel, en fazla 10 sn |
| Devralma onayı | 20 sn |

### 7.6 Bit hızı uyarlama (S tarafı, bağlayıcı olmayan referans)

```
her 250 ms:
  congested = send_queue_delay > 50 ms
           || (rapor var && c2r_avg eğimi > +15 ms/sn son 1 sn)
           || frames_dropped_R > %5
  eğer congested ve son düşüşten ≥ 500 ms geçti:
      düşüş dizisinin ilk adımıysa: toparlanma = bitrate × 0.85
      bitrate = max(floor, bitrate × 0.7);  son_düşüş = şimdi
  değilse eğer son 2 sn congested değil:
      eğer bitrate < toparlanma:
          bitrate = min(toparlanma, bitrate × 1.07)   # ≈ +%31/sn
      değilse:
          bitrate = min(ceiling, bitrate × 1.02)      # ≈ +%8/sn
  değişim ≥ %5 ise PARAMETER_KEY_VIDEO_BITRATE uygula
```

Bit hızı tek başına yetmezse S ön ayarı da değiştirebilir. Bu geçiş yeni bir STREAM_CONFIG ve CONFIG ile bildirilir; tel formatı değişmez:

```
bitrate ≤ floor × 1.05 iken 8 sn tıkanıklık sürerse:  bir alt ön ayar
bitrate ≥ ceiling × 0.9 iken N sn kararlı kalırsa:    bir üst ön ayar (seçilen ön ayarı aşmaz)
N = 60 sn; yükseldikten sonra 30 sn içinde inerse N iki katına çıkar (en çok 480 sn)
iki geçiş arasında en az 15 sn; elle verilen akış değerleri varsa geçiş yapılmaz
```

## 8. Keşif ve QR

**QR içeriği** (URI, ~150 karakter, QR sürüm ≈ 8, düzeltme seviyesi M):

```
yansit://c?v=1&h=192.168.1.20,10.42.0.1&p=47800&t=<token>&f=<fp>&n=<ad>
```

| Param | Anlam |
|---|---|
| `v` | QR formatı sürümü (`1`) |
| `h` | Virgülle ayrılmış IPv4 adresleri (en fazla 4; Ethernet önce) |
| `p` | Port |
| `t` | 16 baytlık token, base64url, dolgusuz (22 karakter) |
| `f` | Sertifika parmak izi, 32 bayt, base64url, dolgusuz (43 karakter) |
| `n` | Tahta adı, yüzde-kodlu UTF-8 |

Token kullanıldığında veya 10 dk'da bir yenilenir; QR ekranının görünür olmasına bağlı değildir.

**mDNS:** `_yansit._tcp.local`, TXT: `v=1`, `name=<ad>`, `fp=<parmak izinin ilk 16 baytı base64url>`, isteğe bağlı `kind=board|tv|pc|tablet` (yalnız liste simgesi için; bilinmeyen değer yok sayılır). mDNS yalnız bulma içindir; kimlik doğrulama yine PIN/eşleştirme ile yapılır ve tam parmak izi TLS'te görülen değerle `fp` öneki üzerinden kontrol edilir.

## 9. Örnek baytlar (test vektörü tohumları)

**V1 — VIDEO KEYFRAME başlığı**, pts=1 000 000 µs, seq=1, yük 4 bayt `00 00 00 01`:
```
59 54 01 01 00 02 00 00  00 00 00 00 00 0F 42 40
00 00 00 01 00 00 00 04  00 00 00 01
```

**V2 — CONTROL PING**, pts=1 000 000, seq=7, ping_id=7, t0=1 000 000:
```
59 54 01 03 00 00 00 00  00 00 00 00 00 0F 42 40
00 00 00 07 00 00 00 14
20 00                                   # msg_type=PING, msg_flags=0
01 00 04 00 00 00 07                    # ping_id = 7
02 00 08 00 00 00 00 00 0F 42 40        # t0_us = 1 000 000
```

**Geçersiz örnekler (beklenen sonuç):** magic `59 55` → BAD_MAGIC; version `02` → UNSUPPORTED_VERSION; CONTROL length `0x00010001` → FRAME_TOO_LARGE; TLV len taşması → MALFORMED_TLV; aynı tag iki kez → DUPLICATE_TAG; bilinmeyen tag `0x7F` → yok sayılır; bilinmeyen msg_type `0x99` flags=0 → yok sayılır, flags=1 → UNKNOWN_CRITICAL_MESSAGE.

Tam vektör kümesi (`protocol/vectors/vectors.json`) bağımsız Python referans üretecinden (`protocol/gen/`) çıkar: çerçeveler, tüm mesaj tipleri, HMAC kanıtları ve QR URI'leri.

### 9.1 Hata kodları

Kodlayıcı/ayrıştırıcı hataları iki uygulamada da aynı adlarla bulunur ve vektörlerdeki `error` alanıyla karşılaştırılır:

| Ad | Durum |
|---|---|
| BAD_MAGIC | magic ≠ `0x5954` |
| UNSUPPORTED_VERSION | çerçeve sürümü ≠ 1 |
| UNKNOWN_FRAME_TYPE | type ∉ {1,2,3} |
| FRAME_TOO_LARGE | length türün üst sınırını aşıyor |
| TRUNCATED | başlık veya yük eksik |
| EMPTY_CONTROL | CONTROL yükü 2 bayttan kısa |
| MALFORMED_TLV | TLV başlığı/uzunluğu yük sonunu aşıyor |
| DUPLICATE_TAG | aynı tag iki kez |
| MISSING_TAG | zorunlu tag yok |
| BAD_FIELD_LENGTH | sabit uzunluklu tipte len uyuşmazlığı, tek uzunluklu `pairs` |
| BAD_UTF8 | `str` geçerli UTF-8 değil |
| STRING_TOO_LONG | `str` 255 bayttan uzun |
| UNKNOWN_CRITICAL_MESSAGE | tanınmayan msg_type ve CRITICAL bayrağı |

Çerçeve hatalarında (BAD_MAGIC, UNSUPPORTED_VERSION, UNKNOWN_FRAME_TYPE, FRAME_TOO_LARGE, TRUNCATED) çerçeve sınırı kaybolduğu için bağlantı yanıtsız kapatılır. Çerçevesi sağlam ama içeriği hatalı CONTROL mesajı `BYE(MALFORMED)` ile sonuçlanır; medya bağlantısında doğrudan kapatılır.

## 10. Sürümleme

- Çerçeve `version` ve HELLO `version` şu an `1`.
- Geriye uyumlu eklemeler (yeni tag, CRITICAL olmayan yeni msg_type, yeni flag biti) sürüm artırmaz; bu belgenin "Değişiklikler" bölümüne yazılır.
- Uyumsuz değişiklik → sürüm `2`; R `CHALLENGE.min/max_version` ile desteklediği aralığı bildirir, S ortak en yüksek sürümü seçer.

## 11. Alıcı davranışı

Bu bölüm her alıcı uygulaması için bağlayıcıdır. Pardus alıcısı referanstır; ayrışma varsa `tools/conformance` karar verir.

**Kimlik.** Kendinden imzalı X.509 v3 sertifika, TLS 1.3, ALPN `yansit/1`. Parmak izi leaf sertifikanın DER'inin SHA-256'sıdır. Gönderici CA, ad veya süre denetlemez; yalnız parmak izini karşılaştırır. Sertifika süresi dolunca yeniden üretilir. Bu olunca eşleşmiş cihazlar yeniden QR okutur.

**Bağlantı sınırları.** Kimlik doğrulamadan önce en çok 16 bağlantı tutulur. TLS ve ilk mesaj için 10 sn verilir. Sessizlik süresi (5 sn) yalnız CONTROL bağlantısına uygulanır. Medya bağlantısı PAUSE sırasında sessiz kalabilir. Onay bekleyen bağlantıda sessizlik süresi işlemez.

**HELLO sırası.**
1. `version` ≠ 1 → `REJECT(VERSION_UNSUPPORTED)`.
2. Başka bir bağlantı onay bekliyorsa kimlik bilgisi harcanmadan `REJECT(BUSY_DENIED)`.
3. Kimlik bilgisi doğrulanır: hız sınırı, PIN kilidi, token, PIN veya eşleştirme anahtarı.
4. Aktif oturum varsa: aynı `device_id` ile eşleştirme anahtarından gelen cihaz oturumunu onaysız devralır. Diğerleri `PENDING_APPROVAL` alır ve 20 sn içinde onay gelmezse `REJECT(BUSY_TIMEOUT)` ile kapatılır.

**Eşleştirme.** Cihaz başına tek anahtar tutulur; yeni eşleştirme eskisinin yerine geçer. Kalıcı kayıt yalnız yerel uygulama verisindedir ve hiçbir günlüğe yazılmaz.

**ATTACH.** Aynı türde yeni bir ATTACH eski bağlantının yerine geçer ve eskisini kapatır. RESUME penceresinde, yani CONTROL yokken gelen ATTACH `SESSION_UNKNOWN` alır.

**RESUME.** Pencere dolmamışsa ve kanıt doğruysa kabul edilir. Eski CONTROL hâlâ açık olabilir, çünkü S kopmayı R'den önce fark etmiş olabilir: R eski CONTROL ve tüm medya bağlantılarını kapatır, yeni `attach_key` ve `resume_token` üretir.

**Görüntü.** Çözücü önünde 4'ten çok kare 250 ms boyunca birikirse kareler IDR'ye kadar atılır ve `KEYFRAME_REQUEST(BACKLOG_DROPPED)` gönderilir. Her durumda aşılmayan sınır 30 karedir; ilk kare çizilene kadar bu sınır 120'dir. H.265 yalnız donanım çözücü varsa WELCOME'da bildirilir.

**Ses.** Hedef gecikme, görüntünün yakalama→çizim ortalaması + bir ekran karesidir, 40–250 ms ile sınırlıdır. `pts + offset + hedef` anı 5 ms içinde geçmiş olan paket atılır ve `audio_late` artar.

**Keşif.** Hizmet adı UTF-8 karakter ortasından kesilmeden 63 bayta indirilir. Ad boşsa `CastHop` kullanılır. QR'da yalnız yerel IPv4 adresleri bulunur.

**Test sözleşmesi.** Başsız çalışabilen her alıcı `--headless --port N --pairing-file F --stats-file F` seçeneklerini destekler. `pairing-file` JSON: `uri`, `pin`, `port`, `fingerprint`, `token`; token veya PIN değişince yeniden yazılır. `stats-file`: oturum bitince bir JSON satırı, en az `frames_received`, `frames_rendered`, `frames_dropped`, `first_frame_ms`.

## Değişiklikler
- v1-taslak (2026-11-03): ilk tanım.
- v1 (2026-11-03, M1): test vektörleriyle donduruldu. `offset_us` i64, `request_pairing`/`audio_enabled` bool, STREAM_CONFIG ve SENDER_STATS Z/O ayrımı, kanonik tag sırası ve hata kodu listesi eklendi.
- v1 (2026-11-05): tel formatı değişmedi. IDR isteklerinin bekletilmesi (§7.2) ve otomatik ön ayar geçişi (§7.6) eklendi; ürün adı CastHop oldu, tel üzerindeki `yansit` adları korundu.
- v1 (2026-11-05): tel formatı değişmedi. Alıcı davranışı yazıya geçirildi (§11); belge ile Pardus alıcısı arasındaki çelişkiler koda göre düzeltildi.
