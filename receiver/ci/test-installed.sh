#!/bin/sh
# Kurulu .deb paketini dener: /usr/bin'deki ikili (derleme dizininin dışında) arayüzü ve QML modüllerini
# yükleyebilmeli, başsız modda dinlemeye başlamalı. Derleme dizinindeki testlerin göremeyeceği kurulum hatalarını
# yakalar (ör. QML içe aktarma yolları).
#   receiver/ci/test-installed.sh <casthop_*.deb>
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
# A board with a Yansıt 0.1 portable install: the package must clear it from the user's home when it is installed.
id -u ogretmen >/dev/null 2>&1 || useradd -m ogretmen
runuser -u ogretmen -- sh -c 'set -e; opt="$HOME/.local/opt/yansit"; mkdir -p "$opt" "$HOME/.local/share/applications"
    printf "#!/bin/sh\n" >"$opt/yansit-receiver"; chmod +x "$opt/yansit-receiver"
    printf "[Desktop Entry]\nName=Yansıt Alıcı\nExec=%s\n" "$opt/yansit-receiver" \
        >"$HOME/.local/share/applications/io.github.ozkanceng.yansit.receiver.desktop"'
apt-get install -y -qq "$(realpath "$1")" >/dev/null
TEACHER_HOME="$(getent passwd ogretmen | cut -d: -f6)"
test ! -e "$TEACHER_HOME/.local/opt/yansit"
test ! -e "$TEACHER_HOME/.local/share/applications/io.github.ozkanceng.yansit.receiver.desktop"
WORK="$(mktemp -d)"
mkdir -p -m 700 "$WORK/rt"
cd "$WORK"
# Entries left by earlier versions: a user menu entry that hid the system one, an autostart entry under the old name.
mkdir -p "$WORK/.local/share/applications" "$WORK/.config/autostart"
printf '[Desktop Entry]\nType=Application\nName=Yansıt Alıcı\nExec=%s\n' "$WORK/.local/opt/yansit/yansit-receiver" \
    >"$WORK/.local/share/applications/io.github.ozkanceng.yansit.receiver.desktop"
printf '[Desktop Entry]\nType=Application\nName=Yansıt Alıcı\nExec="/usr/bin/yansit-receiver"\n' \
    >"$WORK/.config/autostart/io.github.ozkanceng.yansit.receiver.desktop"
QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software QT_FATAL_WARNINGS=1 XDG_RUNTIME_DIR="$WORK/rt" HOME="$WORK" \
    casthop --windowed --exit-after 3000 --port 47805 --data-dir "$WORK/d1" --qr-host 127.0.0.1 \
    >"$WORK/gui.log" 2>&1 || { cat "$WORK/gui.log"; exit 1; }
XDG_RUNTIME_DIR="$WORK/rt" HOME="$WORK" casthop --headless --exit-after 2000 --port 47806 \
    --data-dir "$WORK/d2" --qr-host 127.0.0.1 >"$WORK/headless.log" 2>&1 || { cat "$WORK/headless.log"; exit 1; }
grep -q "dinleniyor" "$WORK/headless.log"
test -f /usr/share/applications/io.github.ozkanceng.casthop.receiver.desktop
test -x /usr/bin/yansit-receiver # autostart entries from earlier versions
test ! -e "$WORK/.local/share/applications/io.github.ozkanceng.yansit.receiver.desktop"
test ! -e "$WORK/.config/autostart/io.github.ozkanceng.yansit.receiver.desktop"
grep -qx 'Exec="/usr/bin/casthop"' "$WORK/.config/autostart/io.github.ozkanceng.casthop.receiver.desktop"
grep -q "qml6glsink eklentisi: var" "$WORK/gui.log" || { cat "$WORK/gui.log"; exit 1; }
echo "kurulu paket çalışıyor"
