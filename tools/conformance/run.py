#!/usr/bin/env python3
"""Receiver conformance test: any CastHop receiver, driven only through fake-sender.

Starts the receiver from a command template, or uses one that is already running:

  run.py --fake-sender F --receiver 'casthop --headless --port {port} --data-dir {data}
      --pairing-file {pairing} --stats-file {stats} --qr-host 127.0.0.1'
  run.py --fake-sender F --pairing-file P --stats-file S --host 127.0.0.1

Reads the outcome from fake-sender results and the stats file, never from receiver logs.
Test media needs ffmpeg, or pass --media with test.h264, portrait.h264, test.aac and test.opus.
"""
import argparse
import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
NETSIM = HERE.parent / "integration" / "netsim.py"


class Failure(Exception):
    pass


def make_media(out, ffmpeg="ffmpeg"):
    def video(name, size):
        subprocess.run([ffmpeg, "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                        f"testsrc2=size={size}:rate=30", "-t", "10", "-c:v", "libx264", "-preset", "ultrafast",
                        "-tune", "zerolatency", "-pix_fmt", "yuv420p", "-bf", "0", "-g", "15",
                        "-b:v", "3M", "-bsf:v", "h264_mp4toannexb", "-f", "h264", str(out / name)], check=True)

    def audio(name, *codec):
        subprocess.run([ffmpeg, "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                        "sine=frequency=440:sample_rate=48000", "-ac", "2", "-t", "10", *codec, str(out / name)],
                       check=True)

    video("test.h264", "1280x720")
    video("portrait.h264", "720x1280")
    audio("test.aac", "-c:a", "aac", "-f", "adts")
    audio("test.opus", "-c:a", "libopus", "-frame_duration", "20", "-b:a", "96k", "-f", "ogg")


class Run:
    def __init__(self, args, work):
        self.args, self.work = args, work
        self.pairing = Path(args.pairing_file or work / "pairing.json")
        self.stats = Path(args.stats_file) if args.stats_file else (work / "stats.jsonl" if args.receiver else None)
        self.media = Path(args.media) if args.media else work
        self.proc = None
        self.sessions = self.session_count()
        self.passed = []

    def start_receiver(self):
        if not self.args.receiver:
            return
        self.pairing.unlink(missing_ok=True)
        cmd = self.args.receiver.format(port=self.args.port, data=self.work / "data", pairing=self.pairing,
                                        stats=self.stats)
        log = open(self.work / "receiver.log", "wb")
        self.proc = subprocess.Popen(shlex.split(cmd), stdout=log, stderr=subprocess.STDOUT)
        self.wait(lambda: self.pairing.exists() and self.pairing.stat().st_size > 0, 15, "receiver not ready")

    def stop_receiver(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def wait(self, cond, seconds, message):
        end = time.monotonic() + seconds
        while not cond():
            if self.proc and self.proc.poll() is not None:
                raise Failure(f"receiver exited with {self.proc.returncode}: {message}")
            if time.monotonic() > end:
                raise Failure(message)
            time.sleep(0.05)

    def session_count(self):
        if not self.stats or not self.stats.exists():
            return 0
        return sum(1 for _ in self.stats.open(encoding="utf8"))

    def last_session(self):
        lines = self.stats.read_text(encoding="utf8").splitlines()
        return json.loads(lines[-1])

    def sender(self, name, *extra, pairing=None, background=False):
        result = self.work / f"{name}.json"
        cmd = [self.args.fake_sender, "--pairing-file", str(pairing or self.pairing), "--result", str(result)]
        if self.args.host:
            cmd += ["--host", self.args.host]
        cmd += list(extra)
        out = open(self.work / f"{name}.log", "wb")
        proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT)
        if background:
            return proc, result
        return self.finish(name, proc, result)

    def finish(self, name, proc, result):
        code = proc.wait(120)
        if code != 0:
            tail = (self.work / f"{name}.log").read_text(encoding="utf8", errors="replace")[-800:]
            raise Failure(f"fake-sender exit {code}\n{tail}")
        return json.loads(result.read_text(encoding="utf8"))

    def streamed(self, minimum=1):
        """Waits for the session that just ended and checks the receiver really rendered frames."""
        self.sessions += 1
        if not self.stats:
            return None
        self.wait(lambda: self.session_count() >= self.sessions, 10, "session stats not written")
        session = self.last_session()
        if session.get("frames_rendered", 0) < minimum:
            raise Failure(f"rendered {session.get('frames_rendered')} frames: {session}")
        return session

    def scenario(self, name, func):
        print(f"== {name}", flush=True)
        func()
        self.passed.append(name)

    # Scenarios follow docs/PROTOCOL.md §7.1 and §11.

    def bad_token(self):
        self.sender("bad-token", "--auth", "bad-token", "--expect", "reject:AUTH_FAILED")

    def qr_stream(self):
        self.initial = self.work / "pairing-initial.json"
        shutil.copy(self.pairing, self.initial)
        self.sender("qr", "--auth", "qr", "--input", str(self.media / "test.h264"), "--fps", "30",
                    "--frames", "120", "--device-name", "Conformance QR")
        self.streamed()

    def used_token(self):
        self.sender("used-token", "--auth", "qr", "--expect", "reject:AUTH_FAILED", pairing=self.initial)

    def pin(self):
        self.sender("bad-pin", "--auth", "bad-pin", "--expect", "reject:AUTH_FAILED")
        self.sender("pin", "--auth", "pin", "--input", str(self.media / "test.h264"), "--fps", "30",
                    "--frames", "60", "--device-name", "Conformance PIN")
        self.streamed()

    def pairing_key(self):
        key = self.work / "device-key.json"
        common = ["--input", str(self.media / "test.h264"), "--fps", "30", "--frames", "60",
                  "--device-name", "Conformance Pair"]
        self.sender("pair", "--auth", "qr", "--request-pairing", "--pairing-key", str(key), *common)
        self.streamed()
        self.sender("paired", "--auth", "paired", "--pairing-key", str(key), *common)
        self.streamed()

    def rotation(self):
        self.sender("rotate", "--auth", "qr", "--engine", "mirror", "--input", str(self.media / "test.h264"),
                    "--rotate-input", str(self.media / "portrait.h264"), "--rotate-after", "90",
                    "--rotate-size", "720x1280", "--fps", "30", "--frames", "180",
                    "--device-name", "Conformance Rotate")
        self.streamed(90)

    def audio(self, name):
        self.sender(f"audio-{name}", "--auth", "qr", "--engine", "mirror", "--input", str(self.media / "test.h264"),
                    "--audio-input", str(self.media / f"test.{name}"), "--fps", "30", "--frames", "120",
                    "--device-name", f"Conformance {name}")
        session = self.streamed()
        if session is not None and session.get("audio_received", 0) == 0:
            raise Failure(f"no {name} audio arrived: {session}")

    def resume(self):
        pairing = json.loads(self.pairing.read_text(encoding="utf8"))
        proxy_port = self.args.proxy_port
        target = f"{self.args.host or '127.0.0.1'}:{pairing['port']}"
        netsim = subprocess.Popen([sys.executable, "-I", str(NETSIM), "--listen", str(proxy_port), "--target", target,
                                   "--blackout-ms", "1500"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            time.sleep(0.5)
            proc, result = self.sender("resume", "--auth", "qr", "--engine", "mirror", "--port", str(proxy_port),
                                       "--host", "127.0.0.1", "--input", str(self.media / "test.h264"),
                                       "--fps", "30", "--frames", "300", "--device-name", "Conformance Resume",
                                       background=True)
            time.sleep(3)
            netsim.send_signal(signal.SIGUSR1)
            summary = self.finish("resume", proc, result)
            time.sleep(1)
        finally:
            netsim.terminate()
        if summary.get("resume_count", 0) < 1:
            raise Failure(f"session did not resume: {summary}")
        self.streamed()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--fake-sender", required=True)
    p.add_argument("--receiver", help="command template with {port} {data} {pairing} {stats}")
    p.add_argument("--pairing-file", help="pairing file of a receiver that is already running")
    p.add_argument("--stats-file", help="stats file of a receiver that is already running")
    p.add_argument("--host", help="receiver address when it differs from the QR content")
    p.add_argument("--port", type=int, default=47830)
    p.add_argument("--proxy-port", type=int, default=47840)
    p.add_argument("--media", help="directory with ready test media")
    p.add_argument("--ffmpeg", default="ffmpeg")
    p.add_argument("--work", help="working directory (temporary by default)")
    p.add_argument("--skip", action="append", default=[], choices=["opus", "aac", "resume", "rotation"])
    args = p.parse_args()
    if not args.receiver and not args.pairing_file:
        p.error("--receiver or --pairing-file is required")

    work = Path(args.work or tempfile.mkdtemp(prefix="conformance-")).resolve()
    work.mkdir(parents=True, exist_ok=True)
    if not args.media:
        make_media(work, args.ffmpeg)

    run = Run(args, work)
    scenarios = [("wrong token is rejected", run.bad_token), ("QR stream", run.qr_stream),
                 ("used token is rejected", run.used_token), ("PIN", run.pin),
                 ("pairing key", run.pairing_key)]
    if "rotation" not in args.skip:
        scenarios.append(("rotation", run.rotation))
    for codec in ("aac", "opus"):
        if codec not in args.skip:
            scenarios.append((f"{codec} audio", lambda c=codec: run.audio(c)))
    if "resume" not in args.skip and os.name == "posix":
        scenarios.append(("RESUME after a dropped link", run.resume))
    try:
        run.start_receiver()
        for name, func in scenarios:
            run.scenario(name, func)
    except Failure as e:
        print(f"\nFAILED: {e}\nwork dir: {work}", file=sys.stderr)
        return 1
    finally:
        run.stop_receiver()
    print(f"\nALL {len(run.passed)} SCENARIOS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
