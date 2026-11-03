"""Ağ benzetim vekili (tc netem yerine; ayrıcalık gerektirmez).

Gönderici ↔ alıcı TCP bağlantılarını aktarır ve şunları uygular:
  --rate-kbps N   gönderici → alıcı yönünde bant genişliği sınırı (0 = sınırsız)
  --delay-ms N    iki yönde sabit gecikme
  SIGUSR1         "Wi-Fi kopması": --blackout-ms boyunca hiçbir veri aktarılmaz ve yeni bağlantılar
                  reddedilir; süre sonunda açık bağlantılar RST ile düşürülür (gönderici RESUME etmeli)

Küçük soket tamponları kullanılır ki gecikme vekilde birikmesin; geri basınç göndericiye ulaşsın.
Kullanım: netsim.py --listen 47820 --target 127.0.0.1:47810 [--rate-kbps 2500] [--delay-ms 20]
"""
import argparse
import asyncio
import signal
import socket
import sys
import time

CHUNK = 16 * 1024
SOCK_BUF = 64 * 1024


def log(msg):
    print(f"netsim: {msg}", flush=True)


class Sim:
    def __init__(self, args):
        self.args = args
        self.blackout_until = 0.0
        self.transports = set()

    def in_blackout(self):
        return time.monotonic() < self.blackout_until

    def start_blackout(self):
        self.blackout_until = time.monotonic() + self.args.blackout_ms / 1000
        log(f"kopma başladı ({self.args.blackout_ms} ms)")
        asyncio.get_running_loop().call_later(self.args.blackout_ms / 1000, self.end_blackout)

    def end_blackout(self):
        n = len(self.transports)
        for t in list(self.transports):
            t.abort()
        self.transports.clear()
        log(f"kopma bitti, {n} bağlantı düşürüldü")

    async def pump(self, reader, writer, rate_kbps):
        """reader → writer; gecikme kuyruğu ve bant sınırı."""
        queue = asyncio.Queue(maxsize=8)  # küçük: geri basınç göndericiye geçer
        delay = self.args.delay_ms / 1000

        async def read_side():
            try:
                while True:
                    data = await reader.read(CHUNK)
                    if not data:
                        break
                    while self.in_blackout():
                        await asyncio.sleep(0.01)
                    await queue.put((time.monotonic(), data))
            finally:
                await queue.put(None)

        async def write_side():
            next_send = time.monotonic()
            while True:
                item = await queue.get()
                if item is None:
                    break
                t, data = item
                wait = t + delay - time.monotonic()
                if wait > 0:
                    await asyncio.sleep(wait)
                while self.in_blackout():
                    await asyncio.sleep(0.01)
                if rate_kbps > 0:
                    now = time.monotonic()
                    next_send = max(next_send, now)
                    await asyncio.sleep(max(0.0, next_send - now))
                    next_send += len(data) * 8 / (rate_kbps * 1000)
                writer.write(data)
                await writer.drain()

        try:
            await asyncio.gather(read_side(), write_side())
        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            writer.close()

    async def handle(self, c_reader, c_writer):
        if self.in_blackout():
            c_writer.transport.abort()
            return
        tune(c_writer)
        try:
            host, port = self.args.target.rsplit(":", 1)
            u_reader, u_writer = await asyncio.open_connection(host, int(port))
        except OSError as e:
            log(f"hedefe bağlanılamadı: {e}")
            c_writer.transport.abort()
            return
        tune(u_writer)
        self.transports.update({c_writer.transport, u_writer.transport})
        try:
            await asyncio.gather(
                self.pump(c_reader, u_writer, self.args.rate_kbps),
                self.pump(u_reader, c_writer, 0),
            )
        finally:
            self.transports.discard(c_writer.transport)
            self.transports.discard(u_writer.transport)


def tune(writer):
    sock = writer.get_extra_info("socket")
    if sock is not None:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCK_BUF)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCK_BUF)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    writer.transport.set_write_buffer_limits(high=SOCK_BUF)


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen", type=int, required=True)
    p.add_argument("--target", required=True)
    p.add_argument("--rate-kbps", type=int, default=0)
    p.add_argument("--delay-ms", type=int, default=0)
    p.add_argument("--blackout-ms", type=int, default=1500)
    args = p.parse_args()
    sim = Sim(args)
    loop = asyncio.get_running_loop()
    loop.add_signal_handler(signal.SIGUSR1, sim.start_blackout)
    stop = asyncio.Event()
    loop.add_signal_handler(signal.SIGTERM, stop.set)
    server = await asyncio.start_server(sim.handle, "127.0.0.1", args.listen)
    log(f"dinleniyor {args.listen} → {args.target} (rate={args.rate_kbps} kbps, delay={args.delay_ms} ms)")
    async with server:
        await stop.wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
