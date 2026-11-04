#!/bin/sh
# Uzun süre testi (PLAN M7): alıcı + uygulamanın gönderici çekirdeği (fake-sender --engine mirror), N dakika.
#
#   tools/soak/run.sh <casthop> <fake-sender/bin/fake-sender> [dakika=30] [çalışma-dizini]
#
# Çıktı: latency.csv (saniyelik gecikme/fps/bit hızı), rss.csv (10 sn'de bir alıcı belleği), summary.json,
# soak.svg. Kabul: gecikme eğimi ≈ 0 (|eğim| ≤ 0,5 ms/dk), RSS artışı < %5, kare kaybı yok.
# Alıcı ASan'sız (release) derlenmiş olmalı; aksi halde bellek ölçümü anlamsızdır.
set -eu
RECEIVER=$(realpath "$1")
SENDER=$(realpath "$2")
MINUTES=${3:-30}
WORK=${4:-$(mktemp -d)}
mkdir -p "$WORK"
WORK=$(realpath "$WORK")
HERE=$(cd "$(dirname "$0")" && pwd)
PORT=${YANSIT_SOAK_PORT:-47830}
RUNTIME="$WORK/xdg-runtime"
mkdir -p "$RUNTIME" && chmod 700 "$RUNTIME"

RPID=""
cleanup() { [ -n "$RPID" ] && kill "$RPID" 2>/dev/null || true; }
trap cleanup EXIT

echo "== Test videosu (1280x720, 60 fps, GOP 0,5 sn)"
gst-launch-1.0 -q videotestsrc num-buffers=1200 pattern=ball \
    ! video/x-raw,width=1280,height=720,framerate=60/1 \
    ! x264enc tune=zerolatency speed-preset=ultrafast key-int-max=30 bframes=0 bitrate=4000 \
    ! video/x-h264,stream-format=byte-stream,profile=high ! filesink location="$WORK/soak.h264"
gst-launch-1.0 -q audiotestsrc num-buffers=1000 samplesperbuffer=960 wave=sine freq=330 \
    ! audio/x-raw,rate=48000,channels=2 ! opusenc frame-size=20 ! oggmux ! filesink location="$WORK/soak.opus"

echo "== Alıcı"
XDG_RUNTIME_DIR="$RUNTIME" "$RECEIVER" --headless --port "$PORT" --data-dir "$WORK/data" \
    --pairing-file "$WORK/pairing.json" --stats-file "$WORK/stats.jsonl" --qr-host 127.0.0.1 \
    --board-name "Soak" >"$WORK/receiver.log" 2>&1 &
RPID=$!
until [ -s "$WORK/pairing.json" ]; do sleep 0.1; done

echo "== $MINUTES dk akış (görüntü + ses)"
FRAMES=$((MINUTES * 60 * 60))
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --input "$WORK/soak.h264" \
    --audio-input "$WORK/soak.opus" --fps 60 --frames "$FRAMES" --device-name "Soak" \
    --report-csv "$WORK/latency.csv" --result "$WORK/sender.json" >"$WORK/sender.log" 2>&1 &
SPID=$!
echo "t_s,rss_kb" >"$WORK/rss.csv"
START=$(date +%s)
while kill -0 "$SPID" 2>/dev/null; do
    echo "$(( $(date +%s) - START )),$(ps -o rss= -p "$RPID" | tr -d ' ')" >>"$WORK/rss.csv"
    sleep 10
done
wait "$SPID" || { echo "gönderici başarısız"; cat "$WORK/sender.log"; exit 1; }
sleep 1
kill -TERM "$RPID"; wait "$RPID" || true; RPID=""

python3 -I "$HERE/analyze.py" "$WORK"
