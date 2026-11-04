#!/bin/sh
# Taşınabilir paketi temiz bir Debian 12 / Pardus 23 üzerinde dener: yalnız masaüstünde her zaman bulunan
# sistem kütüphaneleri kurulur (gömülmeyenler: GL/EGL, X11/xcb/Wayland, ses istemcisi, fontconfig...).
#   receiver/ci/test-portable.sh yansit-receiver-<sürüm>-<mimari>.tar.gz
set -eu
ARCHIVE="$(realpath "$1")"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
    libgl1 libegl1 libgles2 libopengl0 libgbm1 libdrm2 libx11-6 libx11-xcb1 libxrender1 libxext6 libxfixes3 libxi6 \
    libxrandr2 libxcursor1 libxdamage1 libxcomposite1 libxinerama1 libxss1 libxv1 libxcb1 libxcb-cursor0 libxcb-icccm4 libxcb-image0 \
    libxcb-keysyms1 libxcb-randr0 libxcb-render-util0 libxcb-shape0 libxcb-xfixes0 libxcb-xinerama0 libxcb-xkb1 \
    libxkbcommon0 libxkbcommon-x11-0 libwayland-client0 libwayland-cursor0 libwayland-egl1 libfontconfig1 \
    libfreetype6 libdbus-1-3 libpulse0 libasound2 libglib2.0-0 fonts-noto-core >/dev/null
WORK="$(mktemp -d)"
tar -C "$WORK" -xzf "$ARCHIVE"
DIR="$(echo "$WORK"/yansit-receiver-*)"

# Gömülü her ikili ve eklentinin bağımlılıkları çözülebilmeli.
missing=0
for f in "$DIR/bin/yansit-receiver" "$DIR/libexec/gst-plugin-scanner" $(find "$DIR" -name '*.so*' -type f); do
    if LD_LIBRARY_PATH="$DIR/lib" ldd "$f" 2>/dev/null | grep -q "not found"; then
        echo "eksik bağımlılık: $f"
        LD_LIBRARY_PATH="$DIR/lib" ldd "$f" | grep "not found"
        missing=1
    fi
done
[ "$missing" -eq 0 ] || exit 1

# Başsız modda başlar, TLS sunucusunu ve video/ses çözücüleri yükler, düzgün kapanır.
mkdir -p -m 700 "$WORK/rt"
XDG_RUNTIME_DIR="$WORK/rt" "$DIR/yansit-receiver" --headless --verbose --data-dir "$WORK/data" --qr-host 127.0.0.1 \
    --pairing-file "$WORK/p.json" --exit-after 4000 >"$WORK/log" 2>&1
cat "$WORK/log"
grep -q "dinleniyor" "$WORK/log"
[ -s "$WORK/p.json" ]
# Arayüz: gömülü Qt platform eklentisi ve QML modülleri (QtQuick.Controls vb.) yüklenmeli; uyarı ölümcül.
QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software QT_FATAL_WARNINGS=1 XDG_RUNTIME_DIR="$WORK/rt" \
    "$DIR/yansit-receiver" --windowed --exit-after 3000 --port 47801 --data-dir "$WORK/data" --qr-host 127.0.0.1 \
    >"$WORK/gui.log" 2>&1 || { cat "$WORK/gui.log"; exit 1; }
# The GL sink must load from the bundle; a plugin that fails to load quietly costs GPU rendering.
grep -q "qml6glsink eklentisi: var" "$WORK/gui.log" || { cat "$WORK/gui.log"; exit 1; }
echo "taşınabilir paket temiz sistemde çalışıyor"
