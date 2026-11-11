"""
Builds the app's word dictionaries from English Wiktionary, via kaikki.org's Wiktextract data.

Reads the raw data as a stream on stdin (decompressed on the fly, never saved), keeps entries in
the app's languages, and keeps only what the app uses: spelling, part of speech, IPA (the one
shown, and every pronunciation listed, tab-separated), a few short English meanings, and the word
it is a form of. Writes one gzipped SQLite file per
language, plus a manifest describing them.

    curl -sSL https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz | gzip -dc \
        | python3 tools/build_dictionaries.py --out out --languages app/src/main/java/com/pedant/app/Languages.kt

Each file holds no build date, so an unchanged dictionary gives a byte-identical file.

The data is from Wiktionary (CC BY-SA 4.0), extracted with Wiktextract (Tatu Ylonen, LREC 2022).
"""

import argparse
import gzip
import hashlib
import json
import os
import re
import sqlite3
import sys
import unicodedata

try:
    import orjson

    loads = orjson.loads
except ImportError:  # pragma: no cover
    loads = json.loads

# English Wiktionary's names for each app language.
WIKTIONARY_NAMES = {
    "ar": ["Arabic"], "cs": ["Czech"], "da": ["Danish"], "de": ["German"], "el": ["Greek"],
    "en": ["English"], "es": ["Spanish"], "fi": ["Finnish"], "fr": ["French"], "hi": ["Hindi"],
    "hu": ["Hungarian"], "id": ["Indonesian"], "it": ["Italian"], "nl": ["Dutch"],
    "no": ["Norwegian Bokmål", "Norwegian"], "pl": ["Polish"], "pt": ["Portuguese"],
    "ro": ["Romanian"], "ru": ["Russian"], "sk": ["Slovak"], "sl": ["Slovene"],
    "sr": ["Serbo-Croatian"], "sv": ["Swedish"], "sw": ["Swahili"], "tr": ["Turkish"],
    "uk": ["Ukrainian"], "vi": ["Vietnamese"], "zh": ["Chinese"],
}

# Pronunciation tags to prefer, matching the accent of the app's voice for that language.
PREFERRED_ACCENT = {
    "en": ["US", "General-American"],
    "pt": ["Brazil", "Brazilian-Portuguese"],
    "zh": ["Mandarin"],
}

MAX_SENSES = 3
MAX_GLOSS = 160
MAX_ROWS_PER_WORD = 4
SKIP_SENSE_TAGS = {"obsolete", "archaic", "dated", "rare", "historical"}


def key_of(word):
    """The lookup key; the app normalizes tapped words the same way."""
    return unicodedata.normalize("NFC", word).lower()


def app_codes(languages_kt):
    with open(languages_kt, encoding="utf-8") as f:
        return re.findall(r'Language\("([a-z]{2})"', f.read())


def pick_ipa(sounds, code):
    """The pronunciation shown for the entry (the accent of the app's voice where tagged), and every
    pronunciation listed, for the app to choose the one that matches what's being said."""
    candidates = [s for s in sounds or [] if isinstance(s, dict) and s.get("ipa")]
    if code == "zh":
        # Only Mandarin readings fit the app's voice.
        candidates = [s for s in candidates if "Mandarin" in (s.get("tags") or [])]
    if not candidates:
        return None, []
    shown = candidates[0]
    for tag in PREFERRED_ACCENT.get(code, []):
        tagged = [s for s in candidates if tag in (s.get("tags") or [])]
        if tagged:
            shown = tagged[0]
            break
    variants = []
    for s in [shown] + candidates:
        for v in split_ipa(s["ipa"]):
            if v not in variants:
                variants.append(v)
    first = split_ipa(shown["ipa"])
    return (first[0] if first else None), variants


def split_ipa(ipa):
    """The transcriptions in one IPA field, without their slashes or brackets. A field sometimes
    holds more than one, such as "/ˈsɪti/, [ˈsɪɾi]"."""
    found = re.findall(r"[/\[]([^/\[\]]+)[/\]]", ipa)
    parts = found if found else [ipa]
    return [p.strip() for p in parts if p.strip()]


def pick_senses(senses):
    """A few short meanings, common senses first, and the word this is a form of, if any."""
    kept, form_of = [], None
    usable = [s for s in senses or [] if s.get("glosses")]
    preferred = [s for s in usable if not SKIP_SENSE_TAGS & set(s.get("tags") or [])] or usable
    for s in preferred:
        if form_of is None:
            for link in (s.get("form_of") or []) + (s.get("alt_of") or []):
                if link.get("word"):
                    form_of = link["word"]
                    break
        gloss = s["glosses"][-1].strip()
        if len(gloss) > MAX_GLOSS:
            gloss = gloss[: MAX_GLOSS - 1].rstrip() + "…"
        if gloss and gloss not in kept:
            kept.append(gloss)
        if len(kept) >= MAX_SENSES:
            break
    return kept, form_of


def open_db(path):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute(
        "CREATE TABLE entries (key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT, ipa TEXT, ipas TEXT,"
        " meanings TEXT NOT NULL, form_of TEXT)"
    )
    db.execute("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT)")
    return db


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--languages", required=True, help="Languages.kt, to check every app language is covered")
    args = ap.parse_args()

    codes = app_codes(args.languages)
    missing = [c for c in codes if c not in WIKTIONARY_NAMES]
    if missing:
        sys.exit(f"No Wiktionary name for app languages: {missing}")
    by_name = {name: code for code in codes for name in WIKTIONARY_NAMES[code]}

    os.makedirs(args.out, exist_ok=True)
    dbs = {code: open_db(os.path.join(args.out, f"{code}.db")) for code in codes}
    rows = {code: {} for code in codes}  # key -> number of rows written
    stats = {code: {"entries": 0, "with_ipa": 0} for code in codes}

    lines = 0
    for line in sys.stdin.buffer:
        lines += 1
        if lines % 1_000_000 == 0:
            print(f"{lines:,} lines read", file=sys.stderr, flush=True)
        try:
            entry = loads(line)
        except ValueError:
            continue
        code = by_name.get(entry.get("lang"))
        word = entry.get("word")
        if code is None or not word:
            continue
        meanings, form_of = pick_senses(entry.get("senses"))
        if not meanings:
            continue
        key = key_of(word)
        if rows[code].get(key, 0) >= MAX_ROWS_PER_WORD:
            continue
        rows[code][key] = rows[code].get(key, 0) + 1
        ipa, variants = pick_ipa(entry.get("sounds"), code)
        dbs[code].execute(
            "INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?)",
            (key, word, entry.get("pos"), ipa, "\t".join(variants) or None, "\n".join(meanings), form_of),
        )
        stats[code]["entries"] += 1
        stats[code]["with_ipa"] += ipa is not None

    print(f"{lines:,} lines read in total", file=sys.stderr)
    languages = {}
    for code, db in dbs.items():
        db.execute("CREATE INDEX entries_key ON entries (key)")
        db.executemany(
            "INSERT INTO meta VALUES (?, ?)",
            [
                ("language", code),
                ("source", "English Wiktionary via kaikki.org (Wiktextract)"),
                ("license", "CC BY-SA 4.0; text from Wiktionary contributors"),
            ],
        )
        db.commit()
        db.execute("VACUUM")
        db.close()
        db_path = os.path.join(args.out, f"{code}.db")
        gz_path = db_path + ".gz"
        with open(db_path, "rb") as src, open(gz_path, "wb") as raw:
            # mtime=0 so an unchanged dictionary gives an identical file.
            with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as dst:
                dst.write(src.read())
        os.remove(db_path)
        data = open(gz_path, "rb").read()
        languages[code] = {
            "file": f"{code}.db.gz",
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            **stats[code],
        }
        print(f"{code}: {stats[code]['entries']:,} entries, {stats[code]['with_ipa']:,} with IPA, "
              f"{len(data) / 1e6:.1f} MB", file=sys.stderr)

    with open(os.path.join(args.out, "languages.json"), "w", encoding="utf-8") as f:
        json.dump(languages, f, indent=2, sort_keys=True)


if __name__ == "__main__":
    main()
