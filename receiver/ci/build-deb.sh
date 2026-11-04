#!/bin/sh
# .deb paketini derler ve OUT dizinine kopyalar. Etiketli derlemede (GITHUB_REF_NAME=vX.Y.Z)
# debian/changelog sürümü etikete eşitlenir. Kullanım: ci/build-deb.sh <çıktı-dizini>
set -eu
OUT="$(mkdir -p "${1:?çıktı dizini gerekli}" && cd "$1" && pwd)"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp -a "$SRC" "$WORK/casthop"
# Birim testleri depo kökündeki ortak protokol vektörlerini kullanır.
mkdir -p "$WORK/protocol"
cp -a "$SRC/../protocol/vectors" "$WORK/protocol/vectors"
cd "$WORK/casthop"
rm -rf build build-*

case "${GITHUB_REF_NAME:-}" in
v[0-9]*)
    VERSION="${GITHUB_REF_NAME#v}"
    VERSION="$(echo "$VERSION" | tr '-' '~')"
    DEBEMAIL="casthop@users.noreply.github.com" DEBFULLNAME="CastHop Authors" \
        dch --newversion "$VERSION" --distribution unstable --force-distribution "Release $GITHUB_REF_NAME."
    ;;
esac

dpkg-buildpackage -us -uc -b
# Hata ayıklama sembolleri (-dbgsym) dağıtıma alınmaz.
cp "$WORK"/casthop_*.deb "$OUT/"
ls -l "$OUT"
