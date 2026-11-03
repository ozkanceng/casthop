"""Entegrasyon testi kabul ölçütleri (PLAN.md M2). Kullanım: check.py <çalışma-dizini>"""
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
errors = []


def expect(cond, msg):
    print(("  ok   " if cond else "  HATA ") + msg)
    if not cond:
        errors.append(msg)


stats = [json.loads(l) for l in (work / "stats.jsonl").read_text().splitlines() if l.strip()]
expect(len(stats) == 9, f"dokuz oturum istatistiği (bulunan: {len(stats)})")
s = stats[0] if stats else {}
m = stats[1] if len(stats) > 1 else {}
s2 = json.loads((work / "s2.json").read_text())
s3 = json.loads((work / "s3.json").read_text())

print(f"alıcı: {s}")
print(f"gönderici: {s2}")

expect(s2.get("ok") is True and s2.get("frames_sent") == 600, "gönderici 600 kare yolladı")
expect(s.get("frames_received") == 600, f"alıcı 600 kare aldı ({s.get('frames_received')})")
expect(s.get("frames_rendered") == 600, f"600 kare çözülüp çizildi ({s.get('frames_rendered')})")
expect(s.get("frames_dropped") == 0, f"kare kaybı 0 ({s.get('frames_dropped')})")
ff = s.get("first_frame_ms")
expect(ff is not None and ff <= 1000, f"ilk kare ≤ 1000 ms ({ff})")
expect(s2.get("keyframe_requests") == 0, f"anahtar kare isteği yok ({s2.get('keyframe_requests')})")
expect(s2.get("reports", 0) >= 15, f"RECEIVER_REPORT alındı ({s2.get('reports')})")
off = s2.get("clock_offset_us")
expect(off is not None and abs(off) < 5000, f"saat ofseti aynı makinede ≈ 0 ({off} µs)")
expect(s.get("c2d_avg_ms") is not None, f"yakalama→çizim gecikmesi ölçüldü ({s.get('c2d_avg_ms')} ms)")
expect(s3.get("pending_seen") is True, "ikinci gönderici onay ekranına düştü")

s4b = json.loads((work / "s4b.json").read_text())
print(f"MirrorSession: alıcı {m}")
print(f"MirrorSession: gönderici {s4b}")
expect(s4b.get("ok") is True and s4b.get("frames_sent") == 300, "MirrorSession 300 kare yolladı ve BYE ile bitti")
expect(m.get("frames_rendered") == 300 and m.get("frames_dropped") == 0,
       f"MirrorSession akışı: 300 kare çizildi, kayıp 0 ({m.get('frames_rendered')}/{m.get('frames_dropped')})")
expect(s4b.get("keyframe_requests") == 0, f"MirrorSession: gereksiz IDR isteği yok ({s4b.get('keyframe_requests')})")
expect(s4b.get("sender_dropped", 0) == 0, f"MirrorSession: gönderici kuyruğunda kare atılmadı ({s4b.get('sender_dropped')})")

def load(name):
    return json.loads((work / name).read_text())


rot, rot_r = load("s4c.json"), (stats[2] if len(stats) > 2 else {})
print(f"döndürme: {rot} / alıcı {rot_r}")
expect(rot.get("ok") is True and rot.get("restarts") == 1, f"döndürme: kodlayıcı bir kez yeniden başladı ({rot.get('restarts')})")
expect(rot_r.get("frames_rendered") == rot.get("frames_sent") and rot_r.get("frames_dropped") == 0,
       f"döndürme: tüm kareler çizildi, kayıp yok ({rot_r.get('frames_rendered')}/{rot.get('frames_sent')}, "
       f"atılan {rot_r.get('frames_dropped')})")

res, res_r = load("s4d.json"), (stats[3] if len(stats) > 3 else {})
print(f"RESUME: {res} / alıcı {res_r}")
rc = res.get("reconnect_ms", [])
expect(res.get("ok") is True and res.get("resume_count") == 1, f"Wi-Fi kopması: tek RESUME ({res.get('resume_count')})")
expect(len(rc) == 1 and rc[0] <= 3000, f"Wi-Fi kopması: bağlantı ≤ 3 sn'de geri geldi ({rc} ms)")
expect(res_r.get("frames_rendered", 0) >= 0.7 * res.get("frames_sent", 1),
       f"Wi-Fi kopması: aynı oturumda akış sürdü ({res_r.get('frames_rendered')}/{res.get('frames_sent')})")

con, con_r = load("s4e.json"), (stats[4] if len(stats) > 4 else {})
print(f"dar bant: {con} / alıcı {con_r}")
expect(con.get("ok") is True, "dar bant: oturum düzgün bitti")
expect(con.get("final_bitrate_bps", 10**9) <= 2_500_000,
       f"dar bant: bit hızı tabana indi ({con.get('final_bitrate_bps')})")
expect(con.get("drain_count", 0) > 0 or con.get("sender_dropped", 0) > 0,
       f"dar bant: gönderici kare attı (boşaltma {con.get('drain_count')}, atılan {con.get('sender_dropped')})")
# Dosya kaynağı bit hızını düşüremez (≈3 Mbps > 2,5 Mbps): gecikme ancak kare atılarak sınırlı tutulabilir.
samples = con.get("c2d_samples_ms", [])
tail = samples[-5:]
expect(len(samples) >= 5 and max(tail) < 800,
       f"dar bant: gecikme birikmedi (son 5 sn'lik c2d en çok {max(tail) if tail else None} ms < 800; tümü {samples})")

p4f, p4g, p5b = load("s4f.json"), load("s4g.json"), load("s5b.json")
expect(p4f.get("paired") is True, "eşleştirme: QR bağlantısında anahtar verildi")
expect(p4g.get("ok") is True and p4g.get("paired") is False, "eşleştirme: QR'sız bağlanıldı (yeni anahtar istenmedi)")
expect(p5b.get("ok") is True, "eşleştirme: alıcı yeniden başladıktan sonra da geçerli")

for idx, name, label in [(7, "s4h.json", "Opus"), (8, "s4i.json", "AAC")]:
    snd, rcv = load(name), (stats[idx] if len(stats) > idx else {})
    print(f"ses ({label}): gönderici {snd} / alıcı {rcv}")
    sent = snd.get("audio_packets_sent", 0)
    got, played, late = rcv.get("audio_received", 0), rcv.get("audio_played", 0), rcv.get("audio_late", 0)
    expect(snd.get("ok") is True and sent > 100, f"ses ({label}): gönderildi ({sent} paket)")
    expect(got >= 0.95 * sent, f"ses ({label}): alıcıya ulaştı ({got}/{sent})")
    expect(played >= 0.9 * got and late <= 0.05 * got, f"ses ({label}): çalındı ({played}), geç {late}")
    a, v = rcv.get("audio_latency_ms"), rcv.get("c2d_avg_ms")
    # Görüntü çizildiği anda + bir ekran karesi sonra ekranda; ses o ana hizalanır (en az 40 ms tampon).
    if a is not None and v is not None:
        skew = a - (v + 1000 / 60)
        expect(abs(skew) <= 60, f"ses ({label}): dudak senkronu sapması {skew:.1f} ms (≤ ±60)")
    else:
        expect(False, f"ses ({label}): gecikme ölçülemedi (ses {a}, görüntü {v})")
    expect(rcv.get("frames_dropped") == 0, f"ses ({label}): görüntü kaybı yok ({rcv.get('frames_dropped')})")

gui = work / "stats-gui.jsonl"
if gui.exists():
    g = [json.loads(l) for l in gui.read_text().splitlines() if l.strip()]
    expect(len(g) == 1, f"arayüz modu: tek oturum ({len(g)})")
    g = g[0] if g else {}
    print(f"arayüz modu: {g}")
    # Yazılım OpenGL (llvmpipe) 60 fps'e yetişemeyebilir; o zaman alıcı gecikme biriktirmek yerine kare atar.
    # Burada GL yolunun çalıştığı ve her karenin hesabının verildiği denetlenir; kayıpsızlık başsız senaryoda.
    g6 = json.loads((work / "s6.json").read_text())
    rec, ren, drop = g.get("frames_received", 0), g.get("frames_rendered", 0), g.get("frames_dropped", 0)
    expect(ren + drop == rec, f"arayüz modu: çizilen + atılan = alınan ({ren} + {drop} = {rec})")
    expect(ren >= 0.9 * g6.get("frames_sent", 300), f"arayüz modu: qml6glsink karelerin ≥ %90'ını çizdi ({ren})")
    ff = g.get("first_frame_ms")
    expect(ff is not None and ff <= 1000, f"arayüz modu: ilk kare ≤ 1000 ms ({ff})")

if errors:
    sys.exit(1)
