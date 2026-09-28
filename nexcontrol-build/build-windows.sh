#!/usr/bin/env bash
set -euo pipefail

export WINEARCH=win64
export WINEPREFIX=/opt/wine
export WINEDLLOVERRIDES="mscoree,mshtml="
export WINEDEBUG=-all

PYEXE='C:\Python311\python.exe'
INNO='C:\InnoSetup7\ISCC.exe'

mkdir -p "$WINEPREFIX" /out

xvfb-run -a wine64 wineboot -u || true

curl -fL --retry 4 https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe -o /tmp/python.exe
xvfb-run -a wine64 /tmp/python.exe /quiet InstallAllUsers=1 TargetDir=C:\Python311 PrependPath=1 Include_test=0 Include_launcher=0 SimpleInstall=1
xvfb-run -a wine64 "$PYEXE" --version

xvfb-run -a wine64 "$PYEXE" -m pip install --upgrade pip wheel setuptools
xvfb-run -a wine64 "$PYEXE" -m pip install -r 'Z:\work\src\host\requirements.txt'
xvfb-run -a wine64 "$PYEXE" -m pip install -r 'Z:\work\src\host\requirements-windows.txt'
xvfb-run -a wine64 "$PYEXE" -m pip install --only-binary=:all: -r 'Z:\work\src\host\requirements-webrtc.txt'

cd /work/src
xvfb-run -a wine64 "$PYEXE" -m PyInstaller   --noconfirm   --clean   --onefile   --windowed   --name NexControlHost   --add-data 'mobile;mobile'   --collect-all aiortc   --collect-all av   --collect-all livekit   host/tray_host.py

test -s /work/src/dist/NexControlHost.exe
mkdir -p /work/src/host/dist
cp /work/src/dist/NexControlHost.exe /work/src/host/dist/NexControlHost.exe

curl -fL --retry 4 https://github.com/jrsoftware/issrc/releases/download/is-7_0_2/innosetup-7.0.2-x64.exe -o /tmp/inno.exe
xvfb-run -a wine64 /tmp/inno.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /DIR=C:\InnoSetup7
test -f '/opt/wine/drive_c/InnoSetup7/ISCC.exe'

cd /work/src/installer
xvfb-run -a wine64 "$INNO" 'Z:\work\src\installer\NexControlHost.iss'

test -s /work/src/installer/Output/NexControlHost-1.1.0-Setup.exe

cp /work/src/host/dist/NexControlHost.exe /out/NexControlHost.exe
cp /work/src/installer/Output/NexControlHost-1.1.0-Setup.exe /out/NexControlHost-1.1.0-Setup.exe

sha256sum /out/NexControlHost.exe | sed 's#  /out/#  #' > /out/NexControlHost.exe.sha256
sha256sum /out/NexControlHost-1.1.0-Setup.exe | sed 's#  /out/#  #' > /out/NexControlHost-1.1.0-Setup.exe.sha256

curl -fL --retry 4 https://nexcontrol-v1-1-android-build-production.up.railway.app/NexControl-v1.1.0-debug.apk -o /out/NexControl-v1.1.0-debug.apk
curl -fL --retry 4 https://nexcontrol-v1-1-android-build-production.up.railway.app/NexControl-v1.1.0-debug.apk.sha256 -o /out/NexControl-v1.1.0-debug.apk.sha256

test -s /out/NexControl-v1.1.0-debug.apk
echo "Windows artifacts:"
ls -lh /out
cat /out/*.sha256
