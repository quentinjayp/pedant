"""
Lists every Piper voice for the app's languages: its quality, speakers, whether it reads espeak-ng
phonemes or plain text, and whether sherpa-onnx packages it (the app downloads sherpa-onnx's
packaging). Run in CI, which can reach Hugging Face.

    python3 tools/list_piper_voices.py
"""

import json
import re
import urllib.parse
import urllib.request

LANGUAGES = "app/src/main/java/com/pedant/app/Languages.kt"
BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
SHERPA = "https://huggingface.co/api/models/csukuangfj/vits-piper-{}/tree/main"


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def packaged(voice):
    try:
        get(SHERPA.format(urllib.parse.quote(voice)))
        return True
    except Exception:
        return False


app = re.findall(r'Language\("([a-z]{2})", "([^"]+)", "[^"]+", listOf\(([^)]*)\)', open(LANGUAGES, encoding="utf-8").read())
voices = json.loads(get(f"{BASE}/voices.json"))
by_family = {}
for key, v in voices.items():
    by_family.setdefault(v["language"]["family"], []).append((key, v))

for code, name, used in app:
    used = re.findall(r'"([^"]+)"', used)
    found = sorted(by_family.get(code, []))
    espeak = []
    lines = []
    for key, v in found:
        config = next(p for p in v["files"] if p.endswith(".onnx.json"))
        kind = json.loads(get(f"{BASE}/{urllib.parse.quote(config)}")).get("phoneme_type", "espeak")
        ok = packaged(key)
        if kind == "espeak" and ok:
            espeak.append(key)
        mark = " (in use)" if key in used else ""
        lines.append(f"    {key}: {v['quality']}, {v['num_speakers']} speaker(s), {kind}, "
                     f"{'in sherpa-onnx' if ok else 'not in sherpa-onnx'}{mark}")
    print(f"{code} {name}: {len(espeak)} usable espeak voice(s) of {len(found)}")
    print("\n".join(lines), flush=True)
