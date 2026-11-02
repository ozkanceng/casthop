#!/bin/sh
# Debian 12 / Pardus 23 derleme bağımlılıkları. CI ve yerel konteyner aynı listeyi kullanır.
set -eu
export DEBIAN_FRONTEND=noninteractive
SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq --no-install-recommends \
    build-essential cmake ninja-build pkg-config git ca-certificates file \
    qt6-base-dev qt6-declarative-dev qt6-tools-dev qt6-tools-dev-tools qt6-l10n-tools \
    libgl-dev libegl-dev \
    qml6-module-qtqml qml6-module-qtquick qml6-module-qtquick-controls qml6-module-qtquick-layouts \
    qml6-module-qtquick-window qml6-module-qtquick-templates qml6-module-qtqml-workerscript \
    libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libgstreamer-plugins-bad1.0-dev \
    gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav \
    gstreamer1.0-gl gstreamer1.0-qt6 \
    libssl-dev libqrencode-dev libavahi-client-dev \
    libgtest-dev \
    debhelper devscripts fakeroot
