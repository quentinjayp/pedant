"""
Checks every language's Piper voice says something: each reads its language's built-in opening (as
the app would, through sherpa-onnx with espeak-ng), and the length and loudness of what it said are
printed. A voice that says nothing, or far too little, is flagged. The audio is written to
<out>/<code>.wav to listen to.

    python3 tools/check_voices.py <voices dir> <out dir>

The voices dir holds the vits-piper-<voice> folders (sherpa-onnx's tts-models releases).
"""

import json
import math
import os
import re
import sys
import wave
from array import array

import sherpa_onnx

VOICES, OUT = sys.argv[1], sys.argv[2]
LANGUAGES = "app/src/main/java/com/pedant/app/Languages.kt"
OPENINGS = "app/src/main/assets/openings.json"

os.makedirs(OUT, exist_ok=True)
openings = json.load(open(OPENINGS, encoding="utf-8"))["openings"]
failed = []
for line in open(LANGUAGES, encoding="utf-8"):
    m = re.search(r'Language\("([a-z]{2})", "([^"]+)", "([^"]+)", "([^"]+)"', line)
    if not m:
        continue
    code, name, native, voice = m.groups()
    if not os.path.isdir(f"{VOICES}/vits-piper-{voice}"):
        print(f"{code} {name}: no voice downloaded")
        failed.append(code)
        continue
    d = f"{VOICES}/vits-piper-{voice}"
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/{voice}.onnx", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
        num_threads=2)))
    raw = next((v for k, v in openings.items() if k.startswith(f"{code}-Learning-Default-Formal-")), None)
    text = re.search(r"REPLY:\s*(.*?)(?:\nENGLISH:|$)", raw, re.S).group(1).strip() if raw else native
    audio = tts.generate(text, sid=0, speed=1.0)
    samples = list(audio.samples)
    seconds = len(samples) / audio.sample_rate if audio.sample_rate else 0.0
    rms = math.sqrt(sum(x * x for x in samples) / len(samples)) if samples else 0.0
    peak = max((abs(x) for x in samples), default=0.0)
    # Speech runs at roughly 12-15 characters a second; far less sound than that means it's broken.
    expected = len(text) / 30
    ok = rms > 0.005 and seconds >= expected
    if not ok:
        failed.append(code)
    print(f"{'OK  ' if ok else 'FAIL'} {code} {name} ({voice}): {seconds:.1f} s for {len(text)} characters, "
          f"rms {rms:.3f}, peak {peak:.2f}, {audio.sample_rate} Hz — {text}", flush=True)
    with wave.open(f"{OUT}/{code}.wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        w.writeframes(array("h", (max(-32768, min(32767, int(x * 32767))) for x in samples)).tobytes())

print(f"\n{len(failed)} voices failed: {failed}" if failed else "\nEvery voice spoke.")
sys.exit(1 if failed else 0)
