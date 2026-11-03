"""Uzun süre testinin çözümlenmesi: gecikme eğimi, RSS artışı, kare kaybı → summary.json + soak.svg.
Kullanım: analyze.py <çalışma-dizini>   (çıkış kodu 1 = kabul ölçütü tutmadı)"""
import csv
import json
import pathlib
import statistics
import sys

work = pathlib.Path(sys.argv[1])


def read(name):
    with open(work / name) as f:
        return list(csv.DictReader(f))


lat = [(float(r["t_s"]), float(r["latency_ms"])) for r in read("latency.csv") if r["latency_ms"]]
rss = [(float(r["t_s"]), float(r["rss_kb"])) for r in read("rss.csv") if r["rss_kb"]]
sessions = [json.loads(l) for l in (work / "stats.jsonl").read_text().splitlines() if l.strip()]
sender = json.loads((work / "sender.json").read_text())


def slope(points):
    """En küçük kareler eğimi (y birimi / sn)."""
    n = len(points)
    mx = sum(p[0] for p in points) / n
    my = sum(p[1] for p in points) / n
    den = sum((p[0] - mx) ** 2 for p in points)
    return sum((p[0] - mx) * (p[1] - my) for p in points) / den if den else 0.0


# İlk dakika ısınma (önbellekler, bit hızı tırmanışı) değerlendirme dışında. Eğim, dakikalık ortancalar
# üzerinden hesaplanır: tek tük anlık sıçramalar (ör. sanal makinede işlemci gecikmesi) eğilimi bozmaz.
steady = [p for p in lat if p[0] >= 60] or lat
minutes = {}
for t, v in steady:
    minutes.setdefault(int(t // 60), []).append(v)
per_minute = [(m * 60.0, statistics.median(v)) for m, v in sorted(minutes.items()) if len(v) >= 30]
lat_slope = slope(per_minute if len(per_minute) >= 3 else steady) * 60  # ms / dk
lat_values = [p[1] for p in steady]
p95 = sorted(lat_values)[int(0.95 * (len(lat_values) - 1))]
warm = [v for t, v in rss if 60 <= t <= 180] or [rss[0][1]]
tail = [v for t, v in rss if t >= rss[-1][0] - 120] or [rss[-1][1]]
rss_growth = (statistics.median(tail) - statistics.median(warm)) / statistics.median(warm) * 100
s = sessions[-1] if sessions else {}
summary = {
    "duration_min": round(lat[-1][0] / 60, 1) if lat else 0,
    "latency_mean_ms": round(statistics.mean(lat_values), 1),
    "latency_p95_ms": p95,
    "latency_slope_ms_per_min": round(lat_slope, 3),
    "rss_start_mb": round(statistics.median(warm) / 1024, 1),
    "rss_end_mb": round(statistics.median(tail) / 1024, 1),
    "rss_growth_percent": round(rss_growth, 2),
    "frames_sent": sender.get("frames_sent"),
    "frames_rendered": s.get("frames_rendered"),
    "frames_dropped": s.get("frames_dropped"),
    "audio_late": s.get("audio_late"),
    "sender_dropped": sender.get("sender_dropped"),
}
checks = {
    "latency_slope": abs(lat_slope) <= 0.5,
    "rss_growth": rss_growth < 5.0,
    # Alıcıda kayıp yok; göndericinin 50 ms kuyruk sınırıyla bilinçli attığı kareler ≤ %0,5 (anlık takılmalar).
    "no_receiver_loss": s.get("frames_dropped") == 0
    and s.get("frames_rendered", 0) + sender.get("sender_dropped", 0) >= sender.get("frames_sent", 0),
    "sender_drops_under_0_5_percent": sender.get("sender_dropped", 0) <= 0.005 * max(sender.get("frames_sent", 1), 1),
    "audio_late_under_1_percent": (s.get("audio_late") or 0) <= 0.01 * max(s.get("audio_received") or 1, 1),
}
summary["checks"] = checks
(work / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
print(json.dumps(summary, indent=2, ensure_ascii=False))


def svg_chart(series, y_label, color, top, height, width=900):
    xs = [p[0] for p in series]
    ys = [p[1] for p in series]
    x0, x1 = min(xs), max(xs) or 1
    y0, y1 = 0, max(ys) * 1.15 or 1
    pts = " ".join(f"{60 + (x - x0) / (x1 - x0 or 1) * (width - 80):.1f},"
                   f"{top + height - (y - y0) / (y1 - y0) * height:.1f}" for x, y in series)
    grid = "".join(
        f'<line x1="60" x2="{width - 20}" y1="{top + height - f * height:.1f}" y2="{top + height - f * height:.1f}" '
        f'stroke="#d0d7de" stroke-width="1"/><text x="54" y="{top + height - f * height + 4:.1f}" '
        f'text-anchor="end" font-size="11" fill="#57606a">{y1 * f:.0f}</text>'
        for f in (0, 0.25, 0.5, 0.75, 1))
    return (f'{grid}<polyline fill="none" stroke="{color}" stroke-width="1.5" points="{pts}"/>'
            f'<text x="60" y="{top - 8}" font-size="13" fill="#24292f" font-weight="600">{y_label}</text>')


w, h = 900, 520
body = svg_chart(lat, "Gecikme (yakalama→çizim, ms)", "#0b5cad", 40, 180, w)
body += svg_chart([(t, v / 1024) for t, v in rss], "Alıcı belleği (RSS, MB)", "#8a5100", 300, 180, w)
body += f'<text x="{w - 20}" y="{h - 8}" text-anchor="end" font-size="11" fill="#57606a">süre (sn)</text>'
(work / "soak.svg").write_text(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
    f'font-family="Noto Sans, sans-serif"><rect width="100%" height="100%" fill="#ffffff"/>{body}</svg>\n')
sys.exit(0 if all(checks.values()) else 1)
