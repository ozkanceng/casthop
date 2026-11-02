# Yansıt

**Wirelessly mirror an Android tablet's screen and audio to Pardus ETAP interactive whiteboards.**
Easy to set up, open source, low latency. ("Yansıt" means "mirror it" in Turkish.)

[Türkçe](README.md) · [Plan (TR)](docs/PLAN.md) · [Architecture (TR)](docs/ARCHITECTURE.md) · [Protocol (TR)](docs/PROTOCOL.md)

> ⚠️ **Work in progress.** No release yet. Progress: [CHANGELOG](CHANGELOG.md).

## What does it do?

Shows everything on the teacher's tablet — lesson apps, videos, photos, handwritten notes — on the classroom whiteboard:

1. Open **Yansıt Receiver** on the board; a large QR code appears.
2. Open **Yansıt** on the tablet, tap **"Connect to board"** and scan the QR code.
3. Approve the screen-sharing prompt. Done.

Goals: 1080p / 60 FPS, ≤ 80 ms end-to-end latency (5 GHz Wi-Fi), audio included, encrypted connection (TLS 1.3), nobody can mirror without pairing or approval on the board.

## Components

| Directory | Contents |
|---|---|
| [`android/`](android/) | Tablet app (Kotlin, Jetpack Compose), Android 10+ |
| [`receiver/`](receiver/) | Pardus desktop receiver (C++20, Qt 6, GStreamer), Pardus ETAP 23 / Debian 12 |
| [`protocol/`](protocol/) | Protocol test vectors |
| [`tools/`](tools/) | Fake sender/receiver, latency measurement, soak test |
| [`web-receiver/`](web-receiver/) | (Later) zero-install browser receiver |
| [`docs/`](docs/) | Plan, architecture, protocol, risks, test checklists |

## Installation

No release yet. Step-by-step instructions for teachers will be added with the first release.

## Troubleshooting

**The tablet cannot find / connect to the board.** Many school networks prevent devices on the same Wi-Fi from talking to each other (*client isolation*). Fix: turn on the tablet's **personal hotspot** and connect the board to it; the QR code on the board updates automatically.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache-2.0](LICENSE). Third-party components: [NOTICE](NOTICE).
