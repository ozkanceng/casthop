#!/bin/sh
# Entegrasyon testinin ek bağımlılıkları (alıcı derleme bağımlılıklarına ek olarak):
# JRE (fake-sender), x264enc (test videosu üretimi), python3 (sonuç denetimi),
# Xvfb + Mesa (GL arayüz yolu: qml6glsink yazılım OpenGL ile).
set -eu
export DEBIAN_FRONTEND=noninteractive
SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq --no-install-recommends \
    openjdk-17-jre-headless gstreamer1.0-plugins-ugly gstreamer1.0-tools python3 procps \
    xvfb xauth libgl1-mesa-dri libegl-mesa0 fonts-noto-core fonts-noto-mono
