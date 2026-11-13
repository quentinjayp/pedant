"""
Packages a Piper voice for sherpa-onnx (the speech library the app uses), as sherpa-onnx packages
its own: the model with sherpa-onnx's settings added, and its symbol list as tokens.txt. For voices
sherpa-onnx hasn't packaged itself. Only for espeak-ng voices (phoneme_type "espeak").

    python3 tools/package_piper_voice.py <voice.onnx> <voice.onnx.json> <out dir>

Writes <out dir>/<voice>.onnx and <out dir>/tokens.txt. Checked against sherpa-onnx's packaging of
fr_FR-siwis-medium, en_US-lessac-medium, de_DE-thorsten-medium and uk_UA-lada-x_low: the same
settings, and the symbols in the voice's own order (less any longer than one character).
"""

import json
import os
import sys

import onnx

model_path, config_path, out = sys.argv[1:4]
config = json.load(open(config_path, encoding="utf-8"))
if config.get("phoneme_type", "espeak") != "espeak":
    sys.exit(f"{config_path}: reads {config['phoneme_type']}, not espeak-ng phonemes")

model = onnx.load(model_path)
settings = {
    "model_type": "vits",
    "comment": "piper",
    "language": config["language"]["name_english"],
    "voice": config["espeak"]["voice"],
    "has_espeak": "1",
    "n_speakers": str(config["num_speakers"]),
    "sample_rate": str(config["audio"]["sample_rate"]),
}
del model.metadata_props[:]
for key, value in settings.items():
    model.metadata_props.add(key=key, value=value)

os.makedirs(out, exist_ok=True)
name = os.path.basename(model_path)
onnx.save(model, os.path.join(out, name))
with open(os.path.join(out, "tokens.txt"), "w", encoding="utf-8") as f:
    for symbol, ids in config["phoneme_id_map"].items():
        # sherpa-onnx reads one character per symbol (as espeak-ng gives them); newer Piper releases
        # add longer ones ("aɪ") that it can't read and never needs.
        if len(symbol) == 1:
            f.write(f"{symbol} {ids[0]}\n")
print(f"{name}: {settings}")
