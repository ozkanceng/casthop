"""Gecikme test deseni videosu için ham I420 kareler üretir (tools/latency/README.md biçimi).

Kare i'nin deseni BASE + i * ARALIK (µs) değerini taşır. fake-sender --pattern-base BASE ile bu kareleri gerçek
gönderim anlarına hizalar (saat ofsetine fark eklenir), böylece alıcı gerçek uçtan uca gecikmeyi ölçer.
Kullanım: make_pattern.py <çıktı.yuv> <kare> <genişlik> <yükseklik> <base_us> <aralık_us>
"""
import sys

out, frames, w, h, base, interval = sys.argv[1], *map(int, sys.argv[2:])


def bits(ts):
    ts &= (1 << 48) - 1
    chk = 0
    for i in range(6):
        chk ^= (ts >> (8 * i)) & 0xFF
    return (0b1010 << 60) | (ts << 12) | (chk << 4) | 0b0101


band = h // 10
with open(out, "wb") as f:
    for i in range(frames):
        b = bits(base + i * interval)
        row = bytes(235 if (b >> (63 - x * 64 // w)) & 1 else 16 for x in range(w))
        body = bytes((x + i * 4) % 200 + 28 for x in range(w))  # hareketli gri desen
        f.write(row * band + body * (h - band))
        f.write(bytes([128]) * (w * h // 2))  # U + V
