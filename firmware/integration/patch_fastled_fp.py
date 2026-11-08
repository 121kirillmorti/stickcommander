Import("env")

# Bruce uses -DFP=1 for the TFT font size. FastLED 3.10.9 uses `FP` as a
# template parameter, so the preprocessor macro destroys FastLED's headers.
# Rename the library's internal identifier before compilation.
#
# FastLED also triggers an Xtensa LTO "windowed longcall crosses 1GB boundary"
# when linked into Bruce. Compile it without LTO and with long calls.
from pathlib import Path
import json
import re

pioenv = env.subst("$PIOENV")
fastled_root = Path(".pio") / "libdeps" / pioenv / "FastLED"
fastled_src = fastled_root / "src"

if fastled_src.exists():
    pattern = re.compile(r"\bFP\b")
    changed = 0
    for path in fastled_src.rglob("*"):
        if path.suffix.lower() not in {".h", ".hpp", ".cpp", ".c"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        updated = pattern.sub("FL_FIXED_POINT_TYPE", text)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    print(f"[patch_fastled_fp] renamed FP in {changed} FastLED source file(s)")

manifest = fastled_root / "library.json"
if manifest.exists():
    data = json.loads(manifest.read_text(encoding="utf-8"))
    build = data.setdefault("build", {})
    flags = build.setdefault("flags", [])
    for flag in ("-fno-lto", "-mlongcalls"):
        if flag not in flags:
            flags.append(flag)
    manifest.write_text(json.dumps(data, indent=4), encoding="utf-8")
    print("[patch_fastled_fp] enabled -fno-lto -mlongcalls for FastLED")
