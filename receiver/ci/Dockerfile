# Yerel geliştirme için CI ile aynı Debian 12 ortamı.
#   docker build -t yansit-deb12 -f receiver/ci/Dockerfile receiver/ci
#   docker run --rm -v "$PWD":/src -w /src yansit-deb12 receiver/ci/build-and-test.sh /tmp/build
FROM debian:12
COPY install-deps.sh /usr/local/bin/install-deps.sh
RUN /usr/local/bin/install-deps.sh && apt-get install -y -qq --no-install-recommends gdb strace && rm -rf /var/lib/apt/lists/*
