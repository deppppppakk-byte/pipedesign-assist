#!/usr/bin/env python3
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
mobile = root / "app/src/main/java/com/nexcontrol/mobile"
tl_path = mobile / "TamishraLiveKit.kt"
vm_path = mobile / "NexControlViewModel.kt"

if not tl_path.is_file() or not vm_path.is_file():
    raise SystemExit("NexControl Android source files not found")

tl = tl_path.read_text(encoding="utf-8")

# LiveKit Android 2.29.x keeps DataPublishReliability in room.track.
tl, replaced = re.subn(
    r"(?m)^import\s+[^\n]*DataPublishReliability\s*$",
    "import io.livekit.android.room.track.DataPublishReliability",
    tl,
)
if replaced == 0 and "DataPublishReliability" in tl:
    package_end = tl.find("\n")
    tl = (
        tl[: package_end + 1]
        + "import io.livekit.android.room.track.DataPublishReliability\n"
        + tl[package_end + 1 :]
    )

if "import kotlinx.coroutines.flow.collect" not in tl:
    imports = list(re.finditer(r"(?m)^import\s+[^\n]+$", tl))
    if imports:
        pos = imports[-1].end()
        tl = tl[:pos] + "\nimport kotlinx.coroutines.flow.collect" + tl[pos:]
    else:
        package_end = tl.find("\n")
        tl = (
            tl[: package_end + 1]
            + "import kotlinx.coroutines.flow.collect\n"
            + tl[package_end + 1 :]
        )

tl_path.write_text(tl, encoding="utf-8")

# Compiler reports a View? expression mismatch at source line 157.
lines = vm_path.read_text(encoding="utf-8").splitlines()
idx = 156
if idx >= len(lines):
    raise SystemExit("NexControlViewModel.kt line 157 missing")

line = lines[idx]
if "as? View" not in line:
    if ": View?" in line and "=" in line:
        left, right = line.split("=", 1)
        lines[idx] = f"{left}= ({right.strip()}) as? View"
    elif re.match(r"^\s*return\s+.+", line):
        indent = line[: len(line) - len(line.lstrip())]
        expr = re.sub(r"^\s*return\s+", "", line).strip()
        lines[idx] = f"{indent}return ({expr}) as? View"
    else:
        fixed = False
        for j in range(max(0, idx - 3), min(len(lines), idx + 4)):
            candidate = lines[j]
            if ": View?" in candidate and "=" in candidate and "as? View" not in candidate:
                left, right = candidate.split("=", 1)
                lines[j] = f"{left}= ({right.strip()}) as? View"
                fixed = True
                break
        if not fixed:
            raise SystemExit("Unable to safely patch View? mismatch")

vm_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

print("NexControl Android source patch applied")
