#!/bin/sh
# Regenerates every PNG, ICO and copied SVG icon from the sources in brand/. macOS only:
# needs swiftc and ImageMagick. Android vectors are written by hand, see brand/README.md.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
swiftc -O "$here/render.swift" -o "$tmp/render"

render() { "$tmp/render" "$here/$1" "$2" "$3" "$4"; }
opaque() { render "$1" "$tmp/o.png" "$3" "$4"; magick "$tmp/o.png" -alpha off -type TrueColor "PNG24:$2"; }

ios="$root/apple/Sender/Assets.xcassets/AppIcon.appiconset"
rec="$root/apple/Receiver/Assets.xcassets/AppIcon.appiconset"
opaque casthop-icon-square.svg "$ios/icon-1024.png" 1024 1024
opaque casthop-icon-square.svg "$rec/icon-1024.png" 1024 1024
for size in 16 32 64; do render apple-mac-small.svg "$rec/mac-$size.png" "$size" "$size"; done
for size in 128 256 512 1024; do render apple-mac.svg "$rec/mac-$size.png" "$size" "$size"; done

tv="$root/apple/Receiver/Assets.xcassets/TVIcon.brandassets"
icon="$tv/App Icon.imagestack"
store="$tv/App Icon - App Store.imagestack"
back=Back.imagestacklayer/Content.imageset
front=Front.imagestacklayer/Content.imageset
opaque apple-tv-back.svg "$icon/$back/back-1x.png" 400 240
opaque apple-tv-back.svg "$icon/$back/back-2x.png" 800 480
opaque apple-tv-back.svg "$store/$back/back-1x.png" 1280 768
render apple-tv-front.svg "$icon/$front/front-1x.png" 400 240
render apple-tv-front.svg "$icon/$front/front-2x.png" 800 480
render apple-tv-front.svg "$store/$front/front-1x.png" 1280 768
opaque apple-tv-shelf.svg "$tv/Top Shelf Image.imageset/shelf-1x.png" 1920 720
opaque apple-tv-shelf.svg "$tv/Top Shelf Image.imageset/shelf-2x.png" 3840 1440
opaque apple-tv-shelf-wide.svg "$tv/Top Shelf Image Wide.imageset/shelf-1x.png" 2320 720
opaque apple-tv-shelf-wide.svg "$tv/Top Shelf Image Wide.imageset/shelf-2x.png" 4640 1440

for size in 16 24 32; do render casthop-icon-small.svg "$tmp/i$size.png" "$size" "$size"; done
for size in 48 64 128 256; do render casthop-icon.svg "$tmp/i$size.png" "$size" "$size"; done
magick "$tmp/i16.png" "$tmp/i24.png" "$tmp/i32.png" "$tmp/i48.png" "$tmp/i64.png" \
    "$tmp/i128.png" "$tmp/i256.png" "$root/receiver/data/windows/casthop.ico"

cp "$here/casthop-icon.svg" "$root/receiver/data/icons/io.github.ozkanceng.yansit.receiver.svg"
cp "$here/casthop-icon-small.svg" "$root/site/favicon.svg"
cp "$here/casthop-logo.svg" "$root/site/logo.svg"
cp "$here/casthop-logo-on-dark.svg" "$root/site/logo-on-dark.svg"
opaque casthop-icon-square.svg "$root/site/apple-touch-icon.png" 180 180
