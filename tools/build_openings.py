"""
Writes the openings that ship with the app (app/src/main/assets/openings.json): Gemma's first line
for every language, built-in scenario and register (formal, casual), plus its English translation.

Gemma is told exactly what the app tells it, from app/src/main/assets/instructions.json, and each
opening is stored under the same key the app looks it up by (Tutor.openingKey). Openings whose key
is already in the file are kept, so only the ones whose instructions changed are written again.

With --dicts (the app's word dictionaries, unzipped), each opening's words are checked against its
language's dictionary: Gemma sometimes writes words that don't exist, or answers in English. An
opening with unknown words gets up to five tries, and the one with the fewest is kept; an existing
one with unknown words is written again once (the file's "retried" list).

    python3 tools/build_openings.py --model gemma.litertlm [--shard 0 --shards 4] --out part.json
    python3 tools/build_openings.py --merge part*.json   # into the assets file
"""

import argparse
import glob
import json
import os
import re
import sqlite3
import sys
import time
import unicodedata

ROOT = "app/src/main"
INSTRUCTIONS = f"{ROOT}/assets/instructions.json"
OPENINGS = f"{ROOT}/assets/openings.json"
LANGUAGES = f"{ROOT}/java/com/pedant/app/Languages.kt"
# Part of each key, as the app names openings (Tutor.openingKey).
MODE = "Learning"
# The app's Register names; Gemma is told them in lower case.
REGISTERS = ["Formal", "Casual"]


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
    """(key, language name, system prompt, start message) for every opening, in a fixed order."""
    out = []
    for code, name in languages():
        for scenario in ins["roles"]:
            system = system_prompt(ins, name, scenario)
            for register in REGISTERS:
                start = ins["start"].replace("{register}", register.lower())
                key = f"{code}-{MODE}-{scenario}-{register}-{java_hash_hex(system + start)}"
                out.append((key, name, system, start))
    return out


# Mandarin is looked up a character at a time, which its dictionary doesn't cover well enough to judge.
UNCHECKED = {"zh"}
TRIES = 5


class Words:
    """Whether a language's words are in its dictionary, as the word bubble looks them up: whole,
    then in their parts at an apostrophe or hyphens ("allez-vous")."""

    def __init__(self, dicts):
        self.dicts = dicts
        self.dbs = {}

    def known(self, code, word):
        if code not in self.dbs:
            path = os.path.join(self.dicts, f"{code}.db")
            self.dbs[code] = sqlite3.connect(path) if os.path.exists(path) else None
        db = self.dbs[code]
        if db is None:
            return True
        key = unicodedata.normalize("NFC", word).lower().replace("’", "'")
        if db.execute("SELECT 1 FROM entries WHERE key = ? LIMIT 1", (key,)).fetchone():
            return True
        cut = next((i for i, c in enumerate(word) if c in "'’"), -1)
        if 0 < cut < len(word) - 1:
            return self.known(code, word[:cut + 1]) and self.known(code, word[cut + 1:])
        parts = [p for p in word.split("-") if p]
        return len(parts) > 1 and all(self.known(code, p) for p in parts)

    def unknown(self, code, text):
        """The words of [text] that aren't in [code]'s dictionary."""
        if code in UNCHECKED:
            return []
        words = [w.strip("-") for w in re.findall(r"[\w'’-]+", text)]
        return [w for w in words if w and not w.isdigit() and not self.known(code, w)]


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


def code_of(key):
    return key.split("-", 1)[0]


def to_write(ins, words):
    """The openings to write: new ones, and (with [words]) existing ones with unknown words not yet retried."""
    data = json.load(open(OPENINGS, encoding="utf-8"))
    existing, retried = data["openings"], set(data.get("retried", []))
    out = []
    for job in jobs(ins):
        key = job[0]
        if key not in existing:
            out.append(job)
        elif words and key not in retried and words.unknown(code_of(key), part(existing[key], "REPLY:")):
            out.append(job)
    return out


def build(args):
    import litert_lm

    ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
    words = Words(args.dicts) if args.dicts else None
    todo = [j for i, j in enumerate(to_write(ins, words)) if i % args.shards == args.shard]
    print(f"{len(todo)} openings to write", flush=True)

    # The app's settings: CPU, the same sampling for replies, and plain sampling for translations.
    exact = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)
    written, retried = {}, []
    with litert_lm.Engine(args.model, backend=litert_lm.Backend.CPU(), max_num_tokens=2048) as engine:
        for key, name, system, start in todo:
            started = time.time()
            # Up to TRIES replies, each with its own sampling, until one has no unknown words; the one
            # with the fewest is kept.
            best, best_unknown = "", None
            for attempt in range(TRIES if words else 1):
                sampling = litert_lm.SamplerConfig(top_k=40, top_p=0.95, temperature=0.7, seed=1 + attempt)
                with engine.create_conversation(system_message=system, sampler_config=sampling) as conv:
                    reply = part(text_of(conv.send_message(start)), "REPLY:")
                if not reply:
                    continue
                unknown = words.unknown(code_of(key), reply) if words else []
                if best_unknown is None or len(unknown) < len(best_unknown):
                    best, best_unknown = reply, unknown
                if not unknown:
                    break
                print(f"{key} try {attempt + 1}: unknown words {unknown}", flush=True)
            reply = best
            if words:
                retried.append(key)
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
    json.dump({"openings": written, "retried": retried}, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def merge(paths):
    """Adds the shards' openings to the assets file, dropping keys the instructions no longer make."""
    ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
    data = json.load(open(OPENINGS, encoding="utf-8"))
    current = {key for key, *_ in jobs(ins)}
    openings = {k: v for k, v in data["openings"].items() if k in current}
    retried = set(data.get("retried", [])) & current
    for path in paths:
        part_data = json.load(open(path, encoding="utf-8"))
        openings.update(part_data["openings"])
        retried.update(part_data["retried"])
    data["openings"] = dict(sorted(openings.items()))
    data["retried"] = sorted(retried)
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
    ap.add_argument("--count", action="store_true", help="print how many openings are to be written")
    ap.add_argument("--dicts", help="the app's word dictionaries, unzipped (<code>.db), to check words against")
    a = ap.parse_args()
    if a.merge is not None:
        merge([p for pattern in a.merge for p in glob.glob(pattern)])
    elif a.count:
        ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
        print(len(to_write(ins, Words(a.dicts) if a.dicts else None)))
    else:
        build(a)
