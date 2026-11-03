# Yansıt

**Mirror an Android tablet's screen and sound wirelessly to Pardus ETAP interactive whiteboards.**
Easy to set up, open source, low latency, encrypted.

[Türkçe](README.md) · [Install](#install) · [Use](#use) · [Troubleshooting](#troubleshooting) ·
[Architecture](docs/ARCHITECTURE.md) · [Protocol](docs/PROTOCOL.md) · [Changelog](CHANGELOG.md)

## What it does

Shows everything on the teacher's tablet — lesson apps, videos, photos, handwritten notes — with sound on the
classroom whiteboard. No cable, account or internet needed; the tablet and the board just need to be on the same
network.

- **1080p, 60 fps**; quality adapts to the network automatically and latency never builds up.
- **Sound**: video and app audio from the board's speakers, in sync with the picture.
- **Secure**: TLS 1.3; the board only accepts a tablet that knows the QR code / code on its screen. If another tablet
  wants to take over, the board asks for **approval**.
- **Easy**: scan the QR once, then one tap on the board's name. Short Wi-Fi drops recover automatically; rotating the
  tablet rotates the picture.

## Install

### Board (Pardus ETAP 23 / Debian 12)

Download one of these from [Releases](https://github.com/ozkanceng/tablet-mirror/releases):

**A. Package (recommended, needs the administrator password)**

```bash
sudo apt install ./yansit-receiver_*_amd64.deb
```

**Yansıt Receiver** appears in the menu. Dependencies (Qt, GStreamer) come from the Pardus repository.

**B. Portable (no administrator password)**

```bash
tar xf yansit-receiver-*-x86_64.tar.gz
cd yansit-receiver-*-x86_64
./install-user.sh
```

It installs into `~/.local/opt/yansit` and adds a menu entry.

> Optionally enable **Settings → "Start Yansıt when the board starts"** on the board.

### Tablet (Android 10 or later)

Download `yansit-*.apk` from [Releases](https://github.com/ozkanceng/tablet-mirror/releases) on the tablet and open it.
Android may ask to allow installing from unknown sources the first time.

## Use

1. Open **Yansıt Receiver** on the board. A large QR code and a 6-digit code appear.
2. On the tablet open **Yansıt**, tap **Connect to board** and scan the QR code.
3. Answer **Start** to Android's "Share your screen?" prompt. Done.

Stop from the tablet (**Stop mirroring** or **Stop** in the notification) or by tapping the board and pressing
**Stop mirroring**. **Pause** keeps the last picture on the board while you do something else on the tablet.

Boards you have connected to are listed under **Remembered boards**; next time, one tap is enough. Without the camera,
use **Connect with code** to pick the board on the network and enter its 6-digit code.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Cannot reach the board" | Are the tablet and board on the same Wi-Fi? Many school networks block devices from seeing each other (*client isolation*). Easiest fix: turn on the tablet's **mobile hotspot**, connect the board to it and scan the new QR. |
| "Yansıt is not running on the board" | Open Yansıt Receiver on the board. If it is open, allow TCP port 47800 in the board's firewall. |
| "The QR code is no longer valid" | Each QR code works once; scan the current one. |
| "Different networks" | Connect the tablet to the board's Wi-Fi or use the hotspot method. |
| Stutter / blur | Tablet **Settings → Picture quality → Balanced** or **Weak network**. Prefer 5 GHz Wi-Fi. |
| No sound | Was the sound permission granted, and is **Settings → Play sound on the board** on? Some apps do not allow their audio to be shared. |
| Mirroring stops when the screen locks | A security rule on Android 15+; increase the screen timeout. |

Help and bug reports: [Issues](https://github.com/ozkanceng/tablet-mirror/issues) — include `yansit-receiver --verbose`
output and the tablet model.

## Measured performance

From automated tests (every change, CI) and the soak test (30 min nightly); on-device measurements are collected with
the checklists in [docs/TESTING.md](docs/TESTING.md).

| Measurement | Result | Target |
|---|---|---|
| First frame after connecting | 50–90 ms (software decoder, CI) | ≤ 1 s |
| Capture→display, same machine | ≈ 1–5 ms (CI) | — |
| Recovery after a Wi-Fi drop | ≈ 20 ms once the network is back (CI) | ≤ 3 s |
| Rotation | reconfiguration without frame loss (CI) | < 1 s |
| Narrow link (2.5 Mbps) | latency < 800 ms, no build-up (CI) | no build-up |
| Audio/video skew | ≈ 22 ms (CI) | ≤ ±60 ms |
| 30-minute soak (video + audio) | latency slope 0.04 ms/min, memory growth 0.0%, 0 frames lost at the receiver, 0.05% late audio ([chart](docs/soak-30min.svg)) | slope ≈ 0, RSS < 5% |
| End-to-end (tablet → board, 5 GHz, 1080p60) | to be measured on devices ([how](tools/latency/README.md)) | ≤ 80 ms (acceptable ≤ 120 ms) |

## Components

| Directory | Content |
|---|---|
| [`android/`](android/) | Tablet app (Kotlin, Jetpack Compose), Android 10+ |
| [`receiver/`](receiver/) | Board receiver (C++20, Qt 6, GStreamer), Pardus ETAP 23 / Debian 12 |
| [`protocol/`](protocol/) | Protocol test vectors and an independent reference generator |
| [`tools/`](tools/) | Fake sender, end-to-end tests, network simulation, latency measurement, soak test |
| [`web-receiver/`](web-receiver/) | (Next phase) browser receiver, no install |
| [`docs/`](docs/) | Plan, architecture, protocol, risks, on-device test checklists |

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md); releases and APK signing: [docs/RELEASING.md](docs/RELEASING.md).

## License

[Apache-2.0](LICENSE). Third-party components: [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
