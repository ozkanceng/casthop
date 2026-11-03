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
expect(len(stats) == 2, f"iki oturum istatistiği (bulunan: {len(stats)})")
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
