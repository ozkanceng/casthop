#!/bin/sh
# Kurulu .deb paketini dener: /usr/bin'deki ikili (derleme dizininin dışında) arayüzü ve QML modüllerini
# yükleyebilmeli, başsız modda dinlemeye başlamalı. Derleme dizinindeki testlerin göremeyeceği kurulum hatalarını
# yakalar (ör. QML içe aktarma yolları).
#   receiver/ci/test-installed.sh <yansit-receiver_*.deb>
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq "$(realpath "$1")" >/dev/null
WORK="$(mktemp -d)"
mkdir -p -m 700 "$WORK/rt"
cd "$WORK"
QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software QT_FATAL_WARNINGS=1 XDG_RUNTIME_DIR="$WORK/rt" HOME="$WORK" \
    yansit-receiver --windowed --exit-after 3000 --port 47805 --data-dir "$WORK/d1" --qr-host 127.0.0.1 \
    >"$WORK/gui.log" 2>&1 || { cat "$WORK/gui.log"; exit 1; }
XDG_RUNTIME_DIR="$WORK/rt" HOME="$WORK" yansit-receiver --headless --exit-after 2000 --port 47806 \
    --data-dir "$WORK/d2" --qr-host 127.0.0.1 >"$WORK/headless.log" 2>&1 || { cat "$WORK/headless.log"; exit 1; }
grep -q "dinleniyor" "$WORK/headless.log"
test -f /usr/share/applications/io.github.ozkanceng.yansit.receiver.desktop
echo "kurulu paket çalışıyor"
