# receiver/ — Yansıt alıcı / receiver

C++20 + Qt 6.4 (QML) + GStreamer 1.22. Hedef: Pardus ETAP 23 / Debian 12, x86_64.

```bash
ci/install-deps.sh                 # Debian 12 / Pardus 23 (sudo)
ci/build-and-test.sh build         # derle + test
./build/src/yansit-receiver --windowed
ci/build-deb.sh out                # .deb paketi
```

Debian dışında (ör. macOS) CI ile aynı ortam:

```bash
docker build -t yansit-deb12 -f receiver/ci/Dockerfile receiver/ci
docker run --rm -v "$PWD":/src -w /src yansit-deb12 receiver/ci/build-and-test.sh /tmp/build
```

Seçenekler: `-DYANSIT_SANITIZE=ON` (ASan + UBSan), `-DYANSIT_BUILD_TESTS=OFF`.
