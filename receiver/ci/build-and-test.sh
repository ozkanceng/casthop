#!/bin/sh
# Alıcıyı derler ve birim testlerini çalıştırır. Kullanım: ci/build-and-test.sh [derleme-dizini] [ek cmake argümanları...]
set -eu
SRC="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${1:-$SRC/build}"
[ $# -gt 0 ] && shift
cmake -S "$SRC" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo "$@"
cmake --build "$BUILD"
QT_QPA_PLATFORM=offscreen ctest --test-dir "$BUILD" --output-on-failure
