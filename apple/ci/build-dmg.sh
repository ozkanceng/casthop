#!/bin/sh
# Builds the Mac receiver as an ad-hoc signed universal app in a .dmg: build-dmg.sh <out-dir> <version> [derived-data].
# Gatekeeper warns about it until it is signed and notarized (docs/RELEASING.md §2b).
set -eu

out=$1
version=$2
root=$(cd "$(dirname "$0")/.." && pwd)
derived=${3:-"$root/build/dmg"}

cd "$root"
xcodegen -q
xcodebuild -project CastHop.xcodeproj -scheme ReceiverApp -configuration Release \
    -destination 'generic/platform=macOS' -derivedDataPath "$derived" \
    ARCHS='arm64 x86_64' ONLY_ACTIVE_ARCH=NO CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= \
    MARKETING_VERSION="$version" build

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -R "$derived/Build/Products/Release/CastHopReceiver.app" "$stage/CastHop.app"
ln -s /Applications "$stage/Applications"
mkdir -p "$out"
hdiutil create -quiet -volname CastHop -srcfolder "$stage" -format UDZO -ov "$out/casthop-mac-$version.dmg"
