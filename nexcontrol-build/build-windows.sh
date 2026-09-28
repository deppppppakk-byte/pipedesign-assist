#!/usr/bin/env bash
set -euo pipefail

export WINEARCH=win64
export WINEPREFIX=/opt/wine
export WINEDLLOVERRIDES="mscoree,mshtml="
export WINEDEBUG=-all

PYEXE='C:\Python311\python.exe'
INNO='C:\InnoSetup7\ISCC.exe'

mkdir -p "$WINEPREFIX" /out

WINE_BIN="$(command -v wine || command -v wine64 || true)"
if [ -z "$WINE_BIN" ] && [ -x /usr/lib/wine/wine64 ]; then
  WINE_BIN=/usr/lib/wine/wine64
fi
if [ -z "$WINE_BIN" ]; then
  echo "Wine executable not found" >&2
  exit 127
fi
echo "Using Wine: $WINE_BIN"
xvfb-run -a "$WINE_BIN" wineboot -u || true

curl -fL --retry 4 https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip -o /tmp/python-embed.zip
mkdir -p "$WINEPREFIX/drive_c/Python311"
unzip -q /tmp/python-embed.zip -d "$WINEPREFIX/drive_c/Python311"
sed -i 's/^#import site/import site/' "$WINEPREFIX/drive_c/Python311/python311._pth"
curl -fL --retry 4 https://bootstrap.pypa.io/get-pip.py -o "$WINEPREFIX/drive_c/Python311/get-pip.py"

xvfb-run -a "$WINE_BIN" "$PYEXE" --version
xvfb-run -a "$WINE_BIN" "$PYEXE" 'C:\Python311\get-pip.py'
xvfb-run -a "$WINE_BIN" "$PYEXE" -m pip install --upgrade pip wheel setuptools
xvfb-run -a "$WINE_BIN" "$PYEXE" -m pip install -r 'Z:\work\src\host\requirements.txt'
xvfb-run -a "$WINE_BIN" "$PYEXE" -m pip install -r 'Z:\work\src\host\requirements-windows.txt'
xvfb-run -a "$WINE_BIN" "$PYEXE" -m pip install --only-binary=:all: "aiortc==1.15.0" "av>=14,<18" "livekit==1.1.19"

cd /work/src
xvfb-run -a "$WINE_BIN" "$PYEXE" -m PyInstaller   --noconfirm   --clean   --onefile   --windowed   --name NexControlHost   --add-data 'mobile;mobile'   --collect-all aiortc   --collect-all av   --collect-all livekit   host/tray_host.py

test -s /work/src/dist/NexControlHost.exe
mkdir -p /work/src/host/dist
cp /work/src/dist/NexControlHost.exe /work/src/host/dist/NexControlHost.exe

curl -fL --retry 4 https://github.com/jrsoftware/issrc/releases/download/is-7_0_2/innosetup-7.0.2-x64.exe -o /tmp/inno.exe
xvfb-run -a "$WINE_BIN" /tmp/inno.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /DIR=C:\InnoSetup7
test -f '/opt/wine/drive_c/InnoSetup7/ISCC.exe'

cd /work/src/installer
xvfb-run -a "$WINE_BIN" "$INNO" 'Z:\work\src\installer\NexControlHost.iss'

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
