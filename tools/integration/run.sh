#!/bin/sh
# Uçtan uca test: sahte gönderici (Kotlin) ↔ başsız alıcı (C++), aynı makinede.
#
#   tools/integration/run.sh <casthop> <fake-sender/bin/fake-sender> [çalışma-dizini]
#
# Senaryolar (PLAN.md M2 kabul ölçütleri):
#   1. Yanlış QR token'ı → REJECT(AUTH_FAILED)
#   2. QR ile 600 karelik 60 fps akış → ilk kare ≤ 1 sn, 600 kare çizilir, kayıp 0
#   3. Akış sürerken ikinci gönderici (PIN) → PENDING_APPROVAL, onay gelmeyince REJECT(BUSY_TIMEOUT)
#   4. Kullanılmış QR token'ı tekrar → REJECT(AUTH_FAILED) (tek kullanımlık)
#   4b. Android uygulamasının gönderici çekirdeği (MirrorSession: kuyruk, bit hızı, IDR) ile 300 kare
#   4c. Döndürme: akış ortasında 1280x720 → 720x1280 yeniden yapılandırma, kare kaybı yok
#   4d. Wi-Fi kopması (netsim, 1,5 sn): oturum RESUME ile ≤ 3 sn'de sürer, tahtada aynı oturum
#   4e. Dar bant (netsim 2,5 Mbps < 4 Mbps akış): gönderici kare atar, gecikme birikmez
#   4f. QR ile bağlanıp kalıcı eşleştirme alma; 4g. eşleştirme anahtarıyla QR'sız bağlanma
#   4h. Görüntü + Opus ses; 4i. görüntü + AAC ses: paketler çalınır, ses görüntüyle senkron (±60 ms)
#   4j. H.265 akışı (CI'da yazılım çözücü YANSIT_ALLOW_SW_HEVC=1 ile sunulur)
#   4k. PIN ile bağlanma (mDNS ile bulunan tahtanın akışı; QR yok)
#   4l. Gecikme deseni: alıcı karedeki damgayı okuyup uçtan uca gecikmeyi ölçer (tools/latency)
#   4m. Otomatik kalite: dar bantta çözünürlük düşer, ağ taşıyınca geri çıkar, oturum kopmaz
#   5. SIGTERM → alıcı düzgün kapanır
#   5b. Alıcı yeniden başlatılınca eşleştirme geçerli (trusted.json kalıcı)
#   6. (YANSIT_IT_GUI=1) Arayüz modu Xvfb + Mesa OpenGL'de: qml6glsink yolu ekranda görüntü var (piksel ölçümü)
#   6b. Aynısı CPU çiziciyle; 6c. GL siyah çizince CPU çiziciye kendiliğinden geçiş
set -eu

RECEIVER=$(realpath "$1")
SENDER=$(realpath "$2")
WORK=${3:-$(mktemp -d)}
mkdir -p "$WORK"
WORK=$(realpath "$WORK")
PORT=${YANSIT_TEST_PORT:-47810}
HERE=$(cd "$(dirname "$0")" && pwd)
RUNTIME="$WORK/xdg-runtime"
mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"

log() { printf '\n== %s\n' "$*"; }
fail() {
    printf '\nBAŞARISIZ: %s\n' "$*" >&2
    printf '\n--- alıcı günlüğü ---\n' >&2
    cat "$WORK/receiver.log" >&2 || true
    exit 1
}

RPID=""
cleanup() { [ -n "$RPID" ] && kill "$RPID" 2>/dev/null || true; }
trap cleanup EXIT

log "Test videosu (1280x720, 60 fps, 600 kare, IDR/2 sn)"
gst-launch-1.0 -q videotestsrc num-buffers=600 pattern=ball \
    ! video/x-raw,width=1280,height=720,framerate=60/1 \
    ! x264enc tune=zerolatency speed-preset=ultrafast key-int-max=120 bframes=0 bitrate=4000 \
    ! video/x-h264,stream-format=byte-stream,profile=high \
    ! filesink location="$WORK/test.h264"
[ -s "$WORK/test.h264" ] || fail "test videosu üretilemedi"
# İsteğe bağlı IDR'yi taklit etmek için kısa GOP'lu (0,5 sn) yatay ve dikey videolar.
for spec in "gop30 1280 720" "portrait 720 1280"; do
    set -- $spec
    gst-launch-1.0 -q videotestsrc num-buffers=600 pattern=smpte \
        ! video/x-raw,width=$2,height=$3,framerate=60/1 \
        ! x264enc tune=zerolatency speed-preset=ultrafast key-int-max=30 bframes=0 bitrate=3000 \
        ! video/x-h264,stream-format=byte-stream,profile=high \
        ! filesink location="$WORK/test-$1.h264"
    [ -s "$WORK/test-$1.h264" ] || fail "test-$1 üretilemedi"
done
# Ses: 10 sn, 48 kHz stereo; Opus 20 ms paketler (Ogg) ve AAC-LC (ADTS).
gst-launch-1.0 -q audiotestsrc num-buffers=500 samplesperbuffer=960 wave=sine freq=440 \
    ! audio/x-raw,rate=48000,channels=2 ! opusenc frame-size=20 bitrate=96000 ! oggmux \
    ! filesink location="$WORK/test.opus"
gst-launch-1.0 -q audiotestsrc num-buffers=470 samplesperbuffer=1024 wave=sine freq=440 \
    ! audio/x-raw,rate=48000,channels=2 ! avenc_aac bitrate=128000 ! aacparse \
    ! audio/mpeg,mpegversion=4,stream-format=adts ! filesink location="$WORK/test.aac"
[ -s "$WORK/test.opus" ] && [ -s "$WORK/test.aac" ] || fail "test sesi üretilemedi"
gst-launch-1.0 -q videotestsrc num-buffers=300 pattern=ball ! video/x-raw,width=1280,height=720,framerate=60/1 \
    ! x265enc tune=zerolatency speed-preset=ultrafast key-int-max=30 bitrate=3000 \
    ! video/x-h265,stream-format=byte-stream ! filesink location="$WORK/test.h265"
[ -s "$WORK/test.h265" ] || fail "H.265 test videosu üretilemedi"
python3 -I "$HERE/make_pattern.py" "$WORK/pattern.yuv" 180 640 360 1000000 16667
gst-launch-1.0 -q filesrc location="$WORK/pattern.yuv" \
    ! rawvideoparse width=640 height=360 format=i420 framerate=60/1 \
    ! x264enc tune=zerolatency speed-preset=ultrafast key-int-max=30 bframes=0 bitrate=3000 \
    ! video/x-h264,stream-format=byte-stream,profile=high ! filesink location="$WORK/pattern.h264"
rm -f "$WORK/pattern.yuv"
[ -s "$WORK/pattern.h264" ] || fail "desen videosu üretilemedi"

log "Alıcı başlatılıyor (port $PORT)"
rm -f "$WORK/pairing.json" "$WORK/stats.jsonl"
XDG_RUNTIME_DIR="$RUNTIME" YANSIT_ALLOW_SW_HEVC=1 "$RECEIVER" --headless --verbose --port "$PORT" \
    --data-dir "$WORK/data" --pairing-file "$WORK/pairing.json" --stats-file "$WORK/stats.jsonl" \
    --approval-timeout 3000 --qr-host 127.0.0.1 --board-name "CI Tahta" >"$WORK/receiver.log" 2>&1 &
RPID=$!
i=0
until [ -s "$WORK/pairing.json" ]; do
    i=$((i + 1))
    [ $i -gt 100 ] && fail "alıcı 10 sn içinde hazır olmadı"
    kill -0 "$RPID" 2>/dev/null || fail "alıcı başlarken kapandı"
    sleep 0.1
done
cp "$WORK/pairing.json" "$WORK/pairing-initial.json"

log "1. Yanlış token reddedilir"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth bad-token --expect reject:AUTH_FAILED \
    --result "$WORK/s1.json" || fail "senaryo 1"

log "2. QR ile 600 kare akış"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --input "$WORK/test.h264" --fps 60 --frames 600 \
    --device-name "Tablet A" --result "$WORK/s2.json" &
SPID=$!

# Akış başlayınca ikinci gönderici denenir.
i=0
until grep -q "ilk kare çizildi" "$WORK/receiver.log"; do
    i=$((i + 1))
    [ $i -gt 100 ] && fail "ilk kare 10 sn içinde çizilmedi"
    sleep 0.1
done

log "3. İkinci gönderici onay bekler ve zaman aşımıyla reddedilir"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth pin --device-name "Tablet B" \
    --expect reject:BUSY_TIMEOUT --expect-pending --result "$WORK/s3.json" || fail "senaryo 3"

wait $SPID || fail "senaryo 2: gönderici başarısız"

log "4. Kullanılmış QR token'ı reddedilir"
"$SENDER" --pairing-file "$WORK/pairing-initial.json" --auth qr --expect reject:AUTH_FAILED \
    --result "$WORK/s4.json" || fail "senaryo 4"

# Oturum istatistiği oturum bitince yazılır (satır sayısı = biten oturum sayısı).
wait_sessions() {
    i=0
    until [ "$(wc -l <"$WORK/stats.jsonl" 2>/dev/null || echo 0)" -ge "$1" ]; do
        i=$((i + 1))
        [ $i -gt 50 ] && fail "oturum istatistiği yazılmadı ($1 bekleniyordu)"
        sleep 0.1
    done
}
wait_sessions 1

log "4b. Uygulamanın gönderici çekirdeği (MirrorSession) ile 300 kare"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --input "$WORK/test.h264" --fps 60 \
    --frames 300 --device-name "Tablet M" --result "$WORK/s4b.json" || fail "senaryo 4b"
wait_sessions 2

log "4c. Döndürme (1280x720 → 720x1280)"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --input "$WORK/test-gop30.h264" \
    --rotate-input "$WORK/test-portrait.h264" --rotate-after 120 --rotate-size 720x1280 --fps 60 --frames 300 \
    --device-name "Tablet R" --result "$WORK/s4c.json" || fail "senaryo 4c"
wait_sessions 3
grep -q "video boyutu: \"720x1280\"" "$WORK/receiver.log" || fail "senaryo 4c: alıcı yeni boyuta geçmedi"

log "4d. Wi-Fi kopması ve RESUME"
python3 -I "$HERE/netsim.py" --listen $((PORT + 10)) --target 127.0.0.1:$PORT --blackout-ms 1500 \
    >"$WORK/netsim-d.log" 2>&1 &
NPID=$!
sleep 0.5
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --port $((PORT + 10)) \
    --input "$WORK/test-gop30.h264" --fps 60 --frames 600 --device-name "Tablet W" --result "$WORK/s4d.json" &
SPID=$!
sleep 3
kill -USR1 "$NPID"
wait $SPID || fail "senaryo 4d: gönderici"
sleep 1 # vekildeki son çerçeveler (BYE) alıcıya ulaşsın
kill "$NPID" 2>/dev/null || true
wait_sessions 4

log "4e. Dar bant (2,5 Mbps, 20 ms)"
python3 -I "$HERE/netsim.py" --listen $((PORT + 11)) --target 127.0.0.1:$PORT --rate-kbps 2500 --delay-ms 20 \
    >"$WORK/netsim-e.log" 2>&1 &
NPID=$!
sleep 0.5
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --port $((PORT + 11)) \
    --input "$WORK/test-gop30.h264" --fps 60 --frames 600 --device-name "Tablet D" --result "$WORK/s4e.json" \
    || fail "senaryo 4e: gönderici"
sleep 1
kill "$NPID" 2>/dev/null || true
wait_sessions 5

log "4f. QR + kalıcı eşleştirme"
rm -f "$WORK/pair.json"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --request-pairing --pairing-key "$WORK/pair.json" \
    --input "$WORK/test.h264" --frames 60 --hold-ms 300 --device-name "Tablet P" --result "$WORK/s4f.json" \
    || fail "senaryo 4f"
[ -s "$WORK/pair.json" ] || fail "senaryo 4f: eşleştirme anahtarı alınmadı"
wait_sessions 6

log "4g. Eşleştirme anahtarıyla QR'sız bağlanma"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth paired --pairing-key "$WORK/pair.json" \
    --input "$WORK/test.h264" --frames 60 --hold-ms 300 --device-name "Tablet P" --result "$WORK/s4g.json" \
    || fail "senaryo 4g"
wait_sessions 7

log "4h. Görüntü + Opus ses"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --input "$WORK/test-gop30.h264" \
    --audio-input "$WORK/test.opus" --fps 60 --frames 300 --device-name "Tablet S" --result "$WORK/s4h.json" \
    || fail "senaryo 4h"
wait_sessions 8

log "4i. Görüntü + AAC ses"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --input "$WORK/test-gop30.h264" \
    --audio-input "$WORK/test.aac" --fps 60 --frames 300 --device-name "Tablet S" --result "$WORK/s4i.json" \
    || fail "senaryo 4i"
wait_sessions 9

log "4j. H.265"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --codec h265 --input "$WORK/test.h265" \
    --fps 60 --frames 300 --device-name "Tablet H" --result "$WORK/s4j.json" || fail "senaryo 4j"
wait_sessions 10

log "4k. PIN ile bağlanma"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth pin --input "$WORK/test.h264" --frames 60 --hold-ms 300 \
    --device-name "Tablet K" --result "$WORK/s4k.json" || fail "senaryo 4k"
wait_sessions 11
"$SENDER" --pairing-file "$WORK/pairing.json" --auth bad-pin --expect reject:AUTH_FAILED --result "$WORK/s4k2.json" \
    || fail "senaryo 4k: yanlış PIN reddedilmeli"

log "4l. Gecikme deseni"
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --input "$WORK/pattern.h264" --fps 60 --frames 180 \
    --pattern-base 1000000 --device-name "Tablet L" --result "$WORK/s4l.json" || fail "senaryo 4l"
wait_sessions 12

log "4m. Otomatik kalite (2,5 Mbps)"
gst-launch-1.0 -q videotestsrc num-buffers=300 pattern=ball \
    ! video/x-raw,width=640,height=360,framerate=60/1 \
    ! x264enc tune=zerolatency speed-preset=ultrafast key-int-max=30 bframes=0 bitrate=500 \
    ! video/x-h264,stream-format=byte-stream,profile=high \
    ! filesink location="$WORK/test-low.h264"
python3 -I "$HERE/netsim.py" --listen $((PORT + 12)) --target 127.0.0.1:$PORT --rate-kbps 2500 --delay-ms 20 \
    >"$WORK/netsim-m.log" 2>&1 &
NPID=$!
sleep 0.5
"$SENDER" --pairing-file "$WORK/pairing.json" --auth qr --engine mirror --port $((PORT + 12)) \
    --input "$WORK/test-gop30.h264" --quality-input "$WORK/test-low.h264" --quality-down-ms 2000 --quality-up-ms 5000 \
    --fps 60 --frames 1800 --device-name "Tablet M" --result "$WORK/s4m.json" || fail "senaryo 4m: gönderici"
sleep 1
kill "$NPID" 2>/dev/null || true
grep -q 'video boyutu: "640x360"' "$WORK/receiver.log" || fail "senaryo 4m: alıcı düşük boyuta geçmedi"
wait_sessions 13

log "5. Düzgün kapanış"
kill -TERM "$RPID"
RC=0
wait "$RPID" || RC=$?
RPID=""
[ "$RC" -eq 0 ] || fail "alıcı çıkış kodu $RC"

log "5b. Yeniden başlatma sonrası eşleştirme"
XDG_RUNTIME_DIR="$RUNTIME" "$RECEIVER" --headless --verbose --port "$PORT" --data-dir "$WORK/data" \
    --pairing-file "$WORK/pairing2.json" --stats-file "$WORK/stats-restart.jsonl" --qr-host 127.0.0.1 \
    --board-name "CI Tahta" >"$WORK/receiver-restart.log" 2>&1 &
RPID=$!
i=0
until [ -s "$WORK/pairing2.json" ]; do
    i=$((i + 1))
    [ $i -gt 100 ] && fail "alıcı yeniden başlamadı"
    sleep 0.1
done
cmp -s "$WORK/pairing.json" "$WORK/pairing2.json" && fail "yeni QR token'ı üretilmemiş"
"$SENDER" --pairing-file "$WORK/pairing2.json" --auth paired --pairing-key "$WORK/pair.json" \
    --input "$WORK/test.h264" --frames 60 --hold-ms 300 --device-name "Tablet P" --result "$WORK/s5b.json" \
    || fail "senaryo 5b"
kill -TERM "$RPID"
wait "$RPID" || fail "senaryo 5b: alıcı çıkışı"
RPID=""

# Screen brightness has to come from the display itself: frame counters stay green over a black window.
gui_scenario() {
    name=$1 pair=$2 stats=$3 result=$4
    shift 4
    rm -f "$pair" "$stats"
    env "$@" XDG_RUNTIME_DIR="$RUNTIME" HOME="$WORK/home-$name" xvfb-run -n 77 -f "$WORK/xauth" \
        -s "-screen 0 1280x720x24" "$RECEIVER" --windowed --verbose \
        --port "$PORT" --data-dir "$WORK/data" --pairing-file "$pair" --stats-file "$stats" \
        --qr-host 127.0.0.1 --board-name "CI Tahta" >"$WORK/receiver.log" 2>&1 &
    RPID=$!
    i=0
    until [ -s "$pair" ]; do
        i=$((i + 1))
        [ $i -gt 150 ] && fail "$name: arayüz 15 sn içinde hazır olmadı"
        kill -0 "$RPID" 2>/dev/null || fail "$name: arayüz başlarken kapandı"
        sleep 0.1
    done
    grep -q "video boru hattı" "$WORK/receiver.log" && fail "$name: boru hattı oturumdan önce kuruldu"
    "$SENDER" --pairing-file "$pair" --auth qr --input "$WORK/test-gop30.h264" --fps 30 --frames 300 \
        --device-name "Tablet GUI" --result "$result" &
    SPID=$!
    sleep 8
    XAUTHORITY="$WORK/xauth" DISPLAY=:77 import -window root "$WORK/$name.png" || fail "$name: ekran görüntüsü alınamadı"
    BRIGHTNESS=$(convert "$WORK/$name.png" -colorspace Gray -format '%[fx:int(mean*255)]' info:)
    wait "$SPID" || fail "$name: gönderici"
    i=0
    until [ -s "$stats" ]; do
        i=$((i + 1))
        [ $i -gt 50 ] && fail "$name: oturum istatistiği yazılmadı"
        sleep 0.1
    done
    # xvfb-run sarmalayıcısı değil, alıcının kendisi sonlandırılır; xvfb-run onun çıkış kodunu döndürür.
    pkill -TERM -x casthop
    RC=0
    wait "$RPID" || RC=$?
    RPID=""
    [ "$RC" -eq 0 ] || fail "$name: çıkış kodu $RC"
    printf '%s: ekran parlaklığı %s\n' "$name" "$BRIGHTNESS"
    [ "$BRIGHTNESS" -ge 60 ] || fail "$name: ekran siyah (parlaklık $BRIGHTNESS)"
}

if [ "${YANSIT_IT_GUI:-0}" = 1 ]; then
    log "6. Arayüz modu (Xvfb, OpenGL)"
    gui_scenario gl "$WORK/pairing-gui.json" "$WORK/stats-gui.jsonl" "$WORK/s6.json" YANSIT_IT=1
    grep -q "çizim: GL" "$WORK/receiver.log" || fail "6: GL çizim kullanılmadı"
    grep -q "görüntü çiziliyor" "$WORK/receiver.log" || fail "6: görüntü denetimi geçmedi"

    log "6b. CPU çizici"
    gui_scenario cpu "$WORK/pairing-cpu.json" "$WORK/stats-cpu.jsonl" "$WORK/s6b.json" YANSIT_RENDERER=cpu
    grep -q "çizim: CPU" "$WORK/receiver.log" || fail "6b: CPU çizim kullanılmadı"

    log "6c. GL siyah çizince CPU çiziciye kendiliğinden geçiş"
    gui_scenario fallback "$WORK/pairing-fb.json" "$WORK/stats-fb.jsonl" "$WORK/s6c.json" YANSIT_TEST_BLACK_GL=1
    grep -q "CPU çizime geçiliyor" "$WORK/receiver.log" || fail "6c: CPU çiziciye geçilmedi"
    grep -q "görüntü çiziliyor" "$WORK/receiver.log" || fail "6c: geçişten sonra görüntü yok"
fi

log "Sonuçlar denetleniyor"
python3 -I "$HERE/check.py" "$WORK" || fail "kabul ölçütleri"
printf '\nTÜM SENARYOLAR GEÇTİ\n'
