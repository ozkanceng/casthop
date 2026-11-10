#!/bin/sh
# Taşınabilir alıcı paketi: casthop-<sürüm>-<mimari>.tar.gz
# Qt, GStreamer (eklentileriyle), OpenSSL, libqrencode, Avahi istemcisi pakete gömülür; grafik sürücüsü katmanı
# (libGL/EGL/VA/DRM), X11/Wayland istemci kütüphaneleri, ses sunucusu istemcisi ve glibc sistemden gelir.
# Yönetici yetkisi gerekmez: `./install-user.sh` kullanıcı dizinine kurar (RISKS R13).
#
#   receiver/ci/build-portable.sh <çıktı-dizini> [sürüm]
set -eu
SRC="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
VERSION="${2:-$(sed -n 's/^ *VERSION \([0-9.]*\)$/\1/p' "$SRC/CMakeLists.txt" | head -1)}"
ARCH="$(uname -m)"
NAME="casthop-$VERSION-$ARCH"
WORK="$(mktemp -d)"
STAGE="$WORK/$NAME"
MULTIARCH="$(gcc -print-multiarch)"
SYSLIB="/usr/lib/$MULTIARCH"

cmake -S "$SRC" -B "$WORK/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DYANSIT_BUILD_TESTS=OFF \
    -DCMAKE_INSTALL_PREFIX=/usr >/dev/null
cmake --build "$WORK/build"
DESTDIR="$WORK/root" cmake --install "$WORK/build" >/dev/null

mkdir -p "$STAGE/bin" "$STAGE/lib" "$STAGE/plugins" "$STAGE/qml" "$STAGE/gstreamer-1.0" "$STAGE/share"
cp "$WORK/root/usr/bin/casthop" "$STAGE/bin/"
cp -r "$WORK/root/usr/share/applications" "$WORK/root/usr/share/icons" "$STAGE/share/"

# Qt eklentileri ve QML modülleri (yalnız kullanılanlar).
QT_PLUGINS="$SYSLIB/qt6/plugins"
for d in platforms xcbglintegrations wayland-shell-integration wayland-graphics-integration-client \
         wayland-decoration-client tls imageformats platforminputcontexts; do
    [ -d "$QT_PLUGINS/$d" ] && cp -r "$QT_PLUGINS/$d" "$STAGE/plugins/"
done
QT_QML="$SYSLIB/qt6/qml"
for m in QtQml QtQuick; do cp -r "$QT_QML/$m" "$STAGE/qml/"; done
# GStreamer eklentileri ve eklenti tarayıcısı.
for p in coreelements app videoparsersbad libav va vaapi videoconvertscale opengl qml6 \
         audioconvert audioresample autodetect pulseaudio pipewire alsa opus typefindfunctions; do
    for f in "$SYSLIB/gstreamer-1.0/libgst$p.so"; do [ -f "$f" ] && cp "$f" "$STAGE/gstreamer-1.0/"; done
done
mkdir -p "$STAGE/libexec"
cp "$SYSLIB/gstreamer1.0/gstreamer-1.0/gst-plugin-scanner" "$STAGE/libexec/"

# Sistemden kullanılacak (gömülmeyecek) kütüphaneler: glibc, GL/EGL/DRM sürücü katmanı, X11/Wayland istemcisi,
# ses sunucusu istemcisi, D-Bus/systemd ve yazı tipi yapılandırması (masaüstünde her zaman bulunur, oturuma bağlıdır).
# libva/libvdpau gömülür (çekirdek kütüphane); VA/VDPAU sürücüleri sistemden yüklenir.
EXCLUDE='^(ld-linux|libc\.|libm\.|libdl\.|libpthread\.|librt\.|libresolv\.|libutil\.|libgcc_s\.|libstdc\+\+\.|libGL|libEGL|libGLX|libGLdispatch|libOpenGL|libgbm|libdrm|libX|libxcb|libxkbcommon|libwayland|libasound|libpulse|libpipewire|libdbus|libsystemd|libudev|libfontconfig|libfreetype|libexpat|libz\.)'
collect() {
    ldd "$1" 2>/dev/null | awk '/=> \//{print $3}' | while read -r lib; do
        base="$(basename "$lib")"
        if ! echo "$base" | grep -Eq "$EXCLUDE" && [ ! -e "$STAGE/lib/$base" ]; then
            cp -L "$lib" "$STAGE/lib/$base"
            collect "$lib"
        fi
    done
}
for f in "$STAGE/bin/casthop" "$STAGE/libexec/gst-plugin-scanner" \
         $(find "$STAGE/plugins" "$STAGE/qml" "$STAGE/gstreamer-1.0" -name '*.so'); do
    collect "$f"
done
# Gömülen Debian paketlerinin listesi (LGPL kaynak teklifi, docs/THIRD_PARTY.md).
for f in "$STAGE"/lib/*.so* "$STAGE"/gstreamer-1.0/*.so; do dpkg -S "/$(basename "$f")" 2>/dev/null | head -1; done \
    | cut -d: -f1 | sort -u | while read -r pkg; do dpkg-query -W -f '${Package} ${Version}\n' "$pkg"; done \
    > "$STAGE/share/bundled-packages.txt"

cat >"$STAGE/casthop" <<'LAUNCH'
#!/bin/sh
# Taşınabilir CastHop alıcısını başlatır (gömülü Qt/GStreamer ile).
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
export YANSIT_LAUNCHER="$HERE/casthop"
export LD_LIBRARY_PATH="$HERE/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export QT_PLUGIN_PATH="$HERE/plugins"
export QML_IMPORT_PATH="$HERE/qml"
export QML2_IMPORT_PATH="$HERE/qml"
export GST_PLUGIN_SYSTEM_PATH_1_0="$HERE/gstreamer-1.0"
export GST_PLUGIN_PATH_1_0=""
export GST_PLUGIN_SCANNER_1_0="$HERE/libexec/gst-plugin-scanner"
export GST_REGISTRY_1_0="${XDG_CACHE_HOME:-$HOME/.cache}/yansit/gst-registry-$(uname -m).bin"
# VA sürücüleri sistemden (donanıma bağlı); bulunamazsa yazılım çözücüye düşülür.
[ -d /usr/lib/$(uname -m)-linux-gnu/dri ] && export LIBVA_DRIVERS_PATH="${LIBVA_DRIVERS_PATH:-/usr/lib/$(uname -m)-linux-gnu/dri}"
mkdir -p "$(dirname "$GST_REGISTRY_1_0")"
exec "$HERE/bin/casthop" "$@"
LAUNCH
chmod +x "$STAGE/casthop"
# Autostart entries written by earlier versions start this path.
ln -s casthop "$STAGE/yansit-receiver"

cat >"$STAGE/install-user.sh" <<'INSTALL'
#!/bin/sh
# Yönetici yetkisi olmadan kullanıcı dizinine kurar: ~/.local/opt/yansit + menü kısayolu.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/.local/opt/yansit"
rm -rf "$DEST"
mkdir -p "$DEST" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/scalable/apps"
cp -r "$HERE"/. "$DEST"/
cp "$HERE/share/icons/hicolor/scalable/apps/io.github.ozkanceng.yansit.receiver.svg" \
    "$HOME/.local/share/icons/hicolor/scalable/apps/"
sed "s|^Exec=.*|Exec=$DEST/casthop|" "$HERE/share/applications/io.github.ozkanceng.casthop.receiver.desktop" \
    > "$HOME/.local/share/applications/io.github.ozkanceng.casthop.receiver.desktop"
# Up to 0.3.3 the entries had another name. The old menu entry would show twice, under the old name.
rm -f "$HOME/.local/share/applications/io.github.ozkanceng.yansit.receiver.desktop"
# An old autostart entry of this install moves to the new name, pointing at the launcher: earlier versions pointed it
# at the bare binary, which is gone and needs the launcher anyway. One the user turned off is dropped.
AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
OLD_AUTOSTART="$AUTOSTART_DIR/io.github.ozkanceng.yansit.receiver.desktop"
if grep -q "^Exec=\"\?$DEST/" "$OLD_AUTOSTART" 2>/dev/null; then
    if ! grep -qE "^(Hidden=true|X-GNOME-Autostart-enabled=false)" "$OLD_AUTOSTART"; then
        printf '[Desktop Entry]\nType=Application\nName=CastHop\nName[en]=CastHop\nIcon=io.github.ozkanceng.yansit.receiver\nExec="%s"\nX-GNOME-Autostart-enabled=true\nNoDisplay=false\n' \
            "$DEST/casthop" >"$AUTOSTART_DIR/io.github.ozkanceng.casthop.receiver.desktop"
    fi
    rm -f "$OLD_AUTOSTART"
fi
echo "Kuruldu: $DEST — menüde 'CastHop'."
INSTALL
chmod +x "$STAGE/install-user.sh"

tar -C "$WORK" -czf "$OUT/$NAME.tar.gz" "$NAME"
du -h "$OUT/$NAME.tar.gz"
rm -rf "$WORK"
