#!/usr/bin/env bash
set -euo pipefail

: "${BUILD_KEY:?BUILD_KEY is required}"

cd /work
cat   windows-source.part00   windows-source.part01   windows-source.part02a   windows-source.part02b   windows-source.part03a   windows-source.part03b   windows-source.part03c   windows-source.part03d > payload.b64

cat windows-source.part04h0 windows-source.part04h1 windows-source.part04h2 windows-source.part04h3   | python3 -c 'import sys; sys.stdout.write(bytes.fromhex(sys.stdin.read().strip()).decode("ascii"))'   >> payload.b64

base64 -d payload.b64 > payload.enc
openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BUILD_KEY -in payload.enc -out payload.tar.xz

echo "5fbf620605e7fb2b340851c833e02f2630b43f4dbf6faf3df249efdd1a1cf425  payload.tar.xz" | sha256sum -c -

mkdir -p /work/src
tar -xJf payload.tar.xz -C /work/src

test -f /work/src/host/tray_host.py
test -f /work/src/host/server.py
test -f /work/src/host/requirements.txt
test -f /work/src/host/requirements-windows.txt
test -f /work/src/host/requirements-webrtc.txt
test -f /work/src/installer/NexControlHost.iss
test -d /work/src/mobile

rm -f payload.b64 payload.enc payload.tar.xz
echo "NexControl Windows source verified and reconstructed."
