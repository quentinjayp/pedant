"""
Writes the openings that ship with the app (app/src/main/assets/openings.json): Gemma's first line
for every language and built-in scenario, plus its English translation.

Gemma is told exactly what the app tells it, from app/src/main/assets/instructions.json, and each
opening is stored under the same key the app looks it up by (Tutor.openingKey). Openings whose key
is already in the file are kept, so only the ones whose instructions changed are written again.

    python3 tools/build_openings.py --model gemma.litertlm [--shard 0 --shards 4] --out part.json
    python3 tools/build_openings.py --merge part*.json   # into the assets file
"""

import argparse
import glob
import json
import re
import sys
import time

ROOT = "app/src/main"
INSTRUCTIONS = f"{ROOT}/assets/instructions.json"
OPENINGS = f"{ROOT}/assets/openings.json"
LANGUAGES = f"{ROOT}/java/com/pedant/app/Languages.kt"
# Part of each key, as the app names openings (Tutor.openingKey).
MODE = "Learning"


def java_hash_hex(text):
    """Integer.toHexString(text.hashCode()), as the app computes it."""
    units = text.encode("utf-16-be")
    h = 0
    for i in range(0, len(units), 2):
        h = (31 * h + int.from_bytes(units[i:i + 2], "big")) & 0xFFFFFFFF
    return format(h, "x")


def languages():
    return re.findall(r'Language\("([a-z]{2})", "([^"]+)"', open(LANGUAGES, encoding="utf-8").read())


def system_prompt(ins, name, scenario):
    role = ins["roles"][scenario]
    opening = ins["openDefault"] if role is None else ins["openRole"].replace("{role}", role)
    return (ins["learning"] + "\n\n" + opening).replace("{language}", name)


def jobs(ins):
    """(key, language name, system prompt) for every opening, in a fixed order."""
    out = []
    for code, name in languages():
        for scenario in ins["roles"]:
            system = system_prompt(ins, name, scenario)
            key = f"{code}-{MODE}-{scenario}-{java_hash_hex(system + ins['start'])}"
            out.append((key, name, system))
    return out


def text_of(message):
    content = message.get("content") if isinstance(message, dict) else message
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(c.get("text", "") for c in content if isinstance(c, dict))
    return str(content)


def part(raw, label):
    """The text after LABEL: up to the next label, as the app's Tutor.parse reads it."""
    m = re.search(re.escape(label) + r"(.*?)(?=\n?(?:CORRECTION|REPLY|ENGLISH|SAY):|\Z)", raw, re.S)
    return m.group(1).strip() if m else ""


def build(args):
    import litert_lm

    ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
    existing = json.load(open(OPENINGS, encoding="utf-8"))["openings"]
    todo = [j for i, j in enumerate(jobs(ins)) if i % args.shards == args.shard and j[0] not in existing]
    print(f"{len(todo)} openings to write", flush=True)

    # The app's settings: CPU, the same sampling for replies, and plain sampling for translations.
    reply_sampling = litert_lm.SamplerConfig(top_k=40, top_p=0.95, temperature=0.7, seed=1)
    exact = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)
    written = {}
    with litert_lm.Engine(args.model, backend=litert_lm.Backend.CPU(), max_num_tokens=2048) as engine:
        for key, name, system in todo:
            started = time.time()
            with engine.create_conversation(system_message=system, sampler_config=reply_sampling) as conv:
                reply = part(text_of(conv.send_message(ins["start"])), "REPLY:")
            if not reply:
                print(f"{key}: no reply, skipped", flush=True)
                continue
            opening = f"REPLY: {reply}"
            translate = ins["replyTranslate"].replace("{language}", name)
            with engine.create_conversation(system_message=translate, sampler_config=exact) as conv:
                english = part(text_of(conv.send_message(reply)), "ENGLISH:")
            if english:
                opening += f"\nENGLISH: {english}"
            written[key] = opening
            print(f"{key} ({time.time() - started:.0f} s): {opening!r}", flush=True)
    json.dump(written, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def merge(paths):
    """Adds the shards' openings to the assets file, dropping keys the instructions no longer make."""
    ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
    data = json.load(open(OPENINGS, encoding="utf-8"))
    current = {key for key, *_ in jobs(ins)}
    openings = {k: v for k, v in data["openings"].items() if k in current}
    for path in paths:
        openings.update(json.load(open(path, encoding="utf-8")))
    data["openings"] = dict(sorted(openings.items()))
    with open(OPENINGS, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    missing = current - openings.keys()
    print(f"{len(openings)} openings; {len(missing)} missing")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model")
    ap.add_argument("--shard", type=int, default=0)
    ap.add_argument("--shards", type=int, default=1)
    ap.add_argument("--out", default="openings-part.json")
    ap.add_argument("--merge", nargs="*")
    ap.add_argument("--count", action="store_true", help="print how many openings are missing")
    a = ap.parse_args()
    if a.merge is not None:
        merge([p for pattern in a.merge for p in glob.glob(pattern)])
    elif a.count:
        ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
        have = json.load(open(OPENINGS, encoding="utf-8"))["openings"]
        print(sum(1 for key, *_ in jobs(ins) if key not in have))
    else:
        build(a)
