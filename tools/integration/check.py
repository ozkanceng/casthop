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
expect(len(stats) == 1, f"tek oturum istatistiği (bulunan: {len(stats)})")
s = stats[0] if stats else {}
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

gui = work / "stats-gui.jsonl"
if gui.exists():
    g = [json.loads(l) for l in gui.read_text().splitlines() if l.strip()]
    expect(len(g) == 1, f"arayüz modu: tek oturum ({len(g)})")
    g = g[0] if g else {}
    print(f"arayüz modu: {g}")
    expect(g.get("frames_rendered") == 300, f"arayüz modu: qml6glsink 300 kare çizdi ({g.get('frames_rendered')})")
    expect(g.get("frames_dropped") == 0, f"arayüz modu: kayıp 0 ({g.get('frames_dropped')})")
    ff = g.get("first_frame_ms")
    expect(ff is not None and ff <= 1000, f"arayüz modu: ilk kare ≤ 1000 ms ({ff})")

if errors:
    sys.exit(1)
