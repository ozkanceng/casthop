#!/bin/sh
# Uçtan uca test: sahte gönderici (Kotlin) ↔ başsız alıcı (C++), aynı makinede.
#
#   tools/integration/run.sh <yansit-receiver> <fake-sender/bin/fake-sender> [çalışma-dizini]
#
# Senaryolar (PLAN.md M2 kabul ölçütleri):
#   1. Yanlış QR token'ı → REJECT(AUTH_FAILED)
#   2. QR ile 600 karelik 60 fps akış → ilk kare ≤ 1 sn, 600 kare çizilir, kayıp 0
#   3. Akış sürerken ikinci gönderici (PIN) → PENDING_APPROVAL, onay gelmeyince REJECT(BUSY_TIMEOUT)
#   4. Kullanılmış QR token'ı tekrar → REJECT(AUTH_FAILED) (tek kullanımlık)
#   5. SIGTERM → alıcı düzgün kapanır
#   6. (YANSIT_IT_GUI=1) Arayüz modu Xvfb + Mesa OpenGL'de: qml6glsink yolu 300 kareyi çizer
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

log "Alıcı başlatılıyor (port $PORT)"
rm -f "$WORK/pairing.json" "$WORK/stats.jsonl"
XDG_RUNTIME_DIR="$RUNTIME" "$RECEIVER" --headless --verbose --port "$PORT" --data-dir "$WORK/data" \
    --pairing-file "$WORK/pairing.json" --stats-file "$WORK/stats.jsonl" \
    --approval-timeout 3000 --qr-host 127.0.0.1 --name "CI Tahta" >"$WORK/receiver.log" 2>&1 &
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

log "5. Düzgün kapanış"
# Oturum istatistiği oturum bitince yazılır; BYE işlenmiş olmalı.
i=0
until [ -s "$WORK/stats.jsonl" ]; do
    i=$((i + 1))
    [ $i -gt 50 ] && fail "oturum istatistiği yazılmadı"
    sleep 0.1
done
kill -TERM "$RPID"
RC=0
wait "$RPID" || RC=$?
RPID=""
[ "$RC" -eq 0 ] || fail "alıcı çıkış kodu $RC"

if [ "${YANSIT_IT_GUI:-0}" = 1 ]; then
    log "6. Arayüz modu (Xvfb, OpenGL)"
    PAIR6="$WORK/pairing-gui.json"
    rm -f "$PAIR6" "$WORK/stats-gui.jsonl"
    XDG_RUNTIME_DIR="$RUNTIME" xvfb-run -a -s "-screen 0 1920x1080x24" "$RECEIVER" --windowed --verbose \
        --port "$PORT" --data-dir "$WORK/data" --pairing-file "$PAIR6" --stats-file "$WORK/stats-gui.jsonl" \
        --qr-host 127.0.0.1 --name "CI Tahta" >"$WORK/receiver.log" 2>&1 &
    RPID=$!
    i=0
    until [ -s "$PAIR6" ]; do
        i=$((i + 1))
        [ $i -gt 150 ] && fail "arayüz modu 15 sn içinde hazır olmadı"
        kill -0 "$RPID" 2>/dev/null || fail "arayüz modu başlarken kapandı"
        sleep 0.1
    done
    grep -q "video boru hattı" "$WORK/receiver.log" && fail "boru hattı oturumdan önce kuruldu"
    "$SENDER" --pairing-file "$PAIR6" --auth qr --input "$WORK/test.h264" --fps 60 --frames 300 \
        --device-name "Tablet GUI" --result "$WORK/s6.json" || fail "senaryo 6: gönderici"
    i=0
    until [ -s "$WORK/stats-gui.jsonl" ]; do
        i=$((i + 1))
        [ $i -gt 50 ] && fail "arayüz modu oturum istatistiği yazılmadı"
        sleep 0.1
    done
    # xvfb-run sarmalayıcısı değil, alıcının kendisi sonlandırılır; xvfb-run onun çıkış kodunu döndürür.
    pkill -TERM -x yansit-receiver
    RC=0
    wait "$RPID" || RC=$?
    RPID=""
    [ "$RC" -eq 0 ] || fail "arayüz modu çıkış kodu $RC"
fi

log "Sonuçlar denetleniyor"
python3 -I "$HERE/check.py" "$WORK" || fail "kabul ölçütleri"
printf '\nTÜM SENARYOLAR GEÇTİ\n'
