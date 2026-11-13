"""
Builds the app's word dictionaries from English Wiktionary, via kaikki.org's Wiktextract data.

Reads the raw data as a stream on stdin (decompressed on the fly, never saved), keeps entries in
the app's languages, and keeps only what the app uses: spelling, part of speech, IPA (the one
shown, and every pronunciation listed, tab-separated), a few short English meanings, and the word
it is a form of. Writes one gzipped SQLite file per
language, plus a manifest describing them.

Each entry also gets how common its part of speech is for that spelling (freq), counted in
Universal Dependencies treebanks, so the app can show "est" (is) before "est" (east). Only
treebanks whose license allows it (no NonCommercial, NoDerivatives or GPL) are counted; a language
with none keeps Wiktionary's order.

    python3 tools/build_dictionaries.py --list-treebanks   # the UD_* repositories to clone into ud/
    curl -sSL https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz | gzip -dc \
        | python3 tools/build_dictionaries.py --out out --ud ud

Each file holds no build date, so an unchanged dictionary gives a byte-identical file.

The data is from Wiktionary (CC BY-SA 4.0), extracted with Wiktextract (Tatu Ylonen, LREC 2022).
The counts are from Universal Dependencies treebanks (https://universaldependencies.org).
"""

import argparse
import glob
import gzip
import hashlib
import json
import os
import re
import sqlite3
import sys

from app_languages import dictionary_key, languages

try:
    import orjson

    loads = orjson.loads
except ImportError:  # pragma: no cover
    loads = json.loads

# English Wiktionary's names for each app language.
WIKTIONARY_NAMES = {
    "cs": ["Czech"], "da": ["Danish"], "de": ["German"], "el": ["Greek"],
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

# Universal Dependencies treebanks to count parts of speech in, per app language. Each one's
# license is checked when it's read (see ud_license_ok); Hungarian's only treebank is
# NonCommercial, so Hungarian has none.
UD_TREEBANKS = {
    "cs": ["Czech-CAC", "Czech-CLTT"], "da": ["Danish-DDT"],
    "de": ["German-GSD", "German-HDT"], "el": ["Greek-GUD"], "en": ["English-EWT"],
    "es": ["Spanish-AnCora", "Spanish-GSD"], "fi": ["Finnish-TDT", "Finnish-FTB"], "fr": ["French-GSD"],
    "hi": ["Hindi-PUD"], "hu": [], "id": ["Indonesian-GSD", "Indonesian-CSUI", "Indonesian-PUD"],
    "it": ["Italian-ParlaMint", "Italian-MarkIT", "Italian-PUD"], "nl": ["Dutch-Alpino", "Dutch-LassySmall"],
    "no": ["Norwegian-Bokmaal"], "pl": ["Polish-MPDT", "Polish-PUD"], "pt": ["Portuguese-GSD", "Portuguese-Bosque"],
    "ro": ["Romanian-RRT"], "ru": ["Russian-GSD", "Russian-Taiga"], "sk": ["Slovak-SNK"],
    "sl": ["Slovenian-SSJ"], "sr": ["Serbian-SET"], "sv": ["Swedish-Talbanken"], "sw": ["Swahili-OPUSGV"],
    "tr": ["Turkish-BOUN", "Turkish-Kenet", "Turkish-Penn"], "uk": ["Ukrainian-ParlaMint"],
    "vi": ["Vietnamese-VTB"], "zh": ["Chinese-GSDSimp"],
}

# Wiktionary's parts of speech for each UD one. A word that's a contraction of two (French "des",
# de + les) is a multiword token in UD, counted as CONTRACTION.
UD_TO_POS = {
    "NOUN": {"noun"}, "PROPN": {"name"}, "VERB": {"verb"}, "AUX": {"verb"}, "ADJ": {"adj"},
    "ADV": {"adv"}, "ADP": {"prep", "postp"}, "PRON": {"pron"}, "DET": {"det", "article"},
    "NUM": {"num"}, "CCONJ": {"conj"}, "SCONJ": {"conj"}, "INTJ": {"intj"}, "PART": {"particle"},
    "CONTRACTION": {"contraction"},
}

MAX_SENSES = 3
MAX_GLOSS = 160
MAX_ROWS_PER_WORD = 4
SKIP_SENSE_TAGS = {"obsolete", "archaic", "dated", "rare", "historical"}


def pick_ipa(sounds, code):
    """The pronunciation shown for the entry: the accent of the app's voice where tagged."""
    candidates = [s for s in sounds or [] if isinstance(s, dict) and s.get("ipa")]
    if code == "zh":
        # Only Mandarin readings fit the app's voice.
        candidates = [s for s in candidates if "Mandarin" in (s.get("tags") or [])]
    if not candidates:
        return None
    shown = candidates[0]
    for tag in PREFERRED_ACCENT.get(code, []):
        tagged = [s for s in candidates if tag in (s.get("tags") or [])]
        if tagged:
            shown = tagged[0]
            break
    first = split_ipa(shown["ipa"])
    return first[0] if first else None


def split_ipa(ipa):
    """The transcriptions in one IPA field, without their slashes or brackets. A field sometimes
    holds more than one, such as "/ˈsɪti/, [ˈsɪɾi]"."""
    found = re.findall(r"[/\[]([^/\[\]]+)[/\]]", ipa)
    parts = found if found else [ipa]
    return [p.strip() for p in parts if p.strip()]


WIKILINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]")


def clean_gloss(gloss):
    """A meaning without wiki link markup that Wiktextract left in ("[[Appendix:…|mi class]]" → "mi class")."""
    return WIKILINK.sub(r"\1", gloss).strip()


def fix_form_links(db):
    """Some "form of" links carry the rest of their sentence ("Abend which was deprecated in the
    spelling reform"); each is cut back to its longest start that is a word in the dictionary."""
    fixed = 0
    for row, base in db.execute("SELECT rowid, form_of FROM entries WHERE form_of LIKE '% %'").fetchall():
        if db.execute("SELECT 1 FROM entries WHERE key = ? LIMIT 1", (dictionary_key(base),)).fetchone():
            continue
        words = base.split(" ")
        for n in range(len(words) - 1, 0, -1):
            prefix = " ".join(words[:n]).rstrip(",;:")
            if db.execute("SELECT 1 FROM entries WHERE key = ? LIMIT 1", (dictionary_key(prefix),)).fetchone():
                db.execute("UPDATE entries SET form_of = ? WHERE rowid = ?", (prefix, row))
                fixed += 1
                break
    return fixed


# A form's own description when Wiktionary doesn't link it to its base word (Swahili "yako": "mi
# class inflection of -ako"): the base word is read from the end of it.
UNLINKED_FORM = re.compile(r"\b(?:inflection|form) of (\S+?)[.,;]?$")


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
        gloss = clean_gloss(s["glosses"][-1])
        if form_of is None:
            unlinked = UNLINKED_FORM.search(gloss)
            if unlinked:
                form_of = unlinked.group(1)
        if len(gloss) > MAX_GLOSS:
            gloss = gloss[: MAX_GLOSS - 1].rstrip() + "…"
        if gloss and gloss not in kept:
            kept.append(gloss)
        if len(kept) >= MAX_SENSES:
            break
    return kept, form_of


def ud_license_ok(treebank_dir):
    """Whether the treebank's license lets its counts go into the CC BY-SA dictionaries."""
    for name in ("README.md", "README.txt"):
        path = os.path.join(treebank_dir, name)
        if os.path.exists(path):
            for line in open(path, encoding="utf-8", errors="replace"):
                if line.lower().startswith("license:"):
                    lic = line.split(":", 1)[1].upper()
                    return "CC" in lic and not any(x in lic for x in ("NC", "ND", "GPL"))
    return False


def ud_counts(code, ud_dir):
    """How often each spelling is each Wiktionary part of speech in [code]'s treebanks, and the
    treebanks counted."""
    counts, used = {}, []
    for treebank in UD_TREEBANKS.get(code, []):
        d = os.path.join(ud_dir, f"UD_{treebank}")
        if not os.path.isdir(d):
            print(f"{code}: {treebank} not found, skipped", file=sys.stderr)
            continue
        if not ud_license_ok(d):
            print(f"{code}: {treebank} license doesn't allow it, skipped", file=sys.stderr)
            continue
        used.append(treebank)
        for path in sorted(glob.glob(os.path.join(d, "*.conllu"))):
            for line in open(path, encoding="utf-8"):
                if not line or line[0] in "#\n":
                    continue
                cols = line.rstrip("\n").split("\t")
                if len(cols) < 4 or cols[1] == "_" or "." in cols[0]:
                    continue
                upos = "CONTRACTION" if "-" in cols[0] else cols[3]
                for pos in UD_TO_POS.get(upos, ()):
                    k = (dictionary_key(cols[1]), pos)
                    counts[k] = counts.get(k, 0) + 1
    return counts, used


# Inflected forms (--forms): the forms Wiktionary lists in a word's conjugation or declension table
# ("bekliyorum" under "beklemek") become entries of their own, "form of <word>", where the dictionary
# has no entry for them. What form each is (person, tense...) is left out: the tables' labels are
# sometimes shifted (Turkish "bekliyorum", I'm waiting, is labelled third person singular).
SKIP_TABLE_TAGS = {"table-tags", "inflection-template", "canonical", "romanization", "class", "error-unknown-tag",
                   "obsolete", "archaic", "dialectal", "rare", "nonstandard", "misspelling", "colloquial"}


# Languages left without table forms: Finnish nouns have hundreds of forms each (cases with possessive
# and clitic endings), which took its dictionary from 15 MB to 485 MB.
NO_TABLE_FORMS = {"fi"}


def table_forms(entry):
    """(form, description) for each form in [entry]'s inflection table."""
    word = entry.get("word") or ""
    out = []
    for f in entry.get("forms") or []:
        form, tags = f.get("form"), f.get("tags") or []
        if not form or form == word or " " in form or form.startswith("-") or not tags or SKIP_TABLE_TAGS & set(tags):
            continue
        out.append((form, f"form of {word}"))
    return out


def add_table_forms(db, freq):
    """Adds the noted table forms the dictionary has no entry for, one per spelling and part of speech."""
    rows = db.execute(
        "SELECT key, word, pos, description, base FROM table_forms "
        "WHERE key NOT IN (SELECT key FROM entries) GROUP BY key, pos ORDER BY key"
    ).fetchall()
    db.executemany(
        "INSERT INTO entries VALUES (?, ?, ?, NULL, ?, ?, ?)",
        [(k, w, pos, d, base, freq.get((k, pos), 0)) for k, w, pos, d, base in rows],
    )
    db.execute("DROP TABLE table_forms")
    return len(rows)


def open_db(path):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute(
        "CREATE TABLE entries (key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT, ipa TEXT,"
        " meanings TEXT NOT NULL, form_of TEXT, freq INTEGER NOT NULL)"
    )
    db.execute("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT)")
    db.execute("CREATE TABLE table_forms (key TEXT, word TEXT, pos TEXT, description TEXT, base TEXT)")
    return db


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    ap.add_argument("--ud", help="the folder the UD_* treebanks are cloned into")
    ap.add_argument("--list-treebanks", action="store_true", help="print the UD_* repositories to clone")
    ap.add_argument("--forms", action="store_true", help="add inflected forms from Wiktionary's tables")
    args = ap.parse_args()
    if args.list_treebanks:
        print("\n".join(f"UD_{t}" for ts in UD_TREEBANKS.values() for t in ts))
        return
    if not args.out:
        ap.error("--out is required")

    codes = [lang.code for lang in languages()]
    missing = [c for c in codes if c not in WIKTIONARY_NAMES]
    if missing:
        sys.exit(f"No Wiktionary name for app languages: {missing}")
    by_name = {name: code for code in codes for name in WIKTIONARY_NAMES[code]}
    missing = [c for c in codes if c not in UD_TREEBANKS]
    if missing:
        sys.exit(f"No treebank list for app languages: {missing}")
    freq, treebanks = {}, {}
    for code in codes:
        freq[code], treebanks[code] = ud_counts(code, args.ud) if args.ud else ({}, [])
        print(f"{code}: {len(freq[code]):,} spellings counted in {', '.join(treebanks[code]) or 'no treebanks'}",
              file=sys.stderr, flush=True)

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
        if args.forms and form_of is None and code not in NO_TABLE_FORMS:
            dbs[code].executemany(
                "INSERT INTO table_forms VALUES (?, ?, ?, ?, ?)",
                [(dictionary_key(f), f, entry.get("pos"), d, word) for f, d in table_forms(entry)],
            )
        key = dictionary_key(word)
        if rows[code].get(key, 0) >= MAX_ROWS_PER_WORD:
            continue
        rows[code][key] = rows[code].get(key, 0) + 1
        ipa = pick_ipa(entry.get("sounds"), code)
        dbs[code].execute(
            "INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?)",
            (key, word, entry.get("pos"), ipa, "\n".join(meanings), form_of,
             freq[code].get((key, entry.get("pos")), 0)),
        )
        stats[code]["entries"] += 1
        stats[code]["with_ipa"] += ipa is not None

    print(f"{lines:,} lines read in total", file=sys.stderr)
    built = {}
    for code, db in dbs.items():
        db.execute("CREATE INDEX entries_key ON entries (key)")
        print(f"{code}: {fix_form_links(db):,} form links cut back to their word", file=sys.stderr)
        added = add_table_forms(db, freq[code])
        stats[code]["entries"] += added
        print(f"{code}: {added:,} forms added from inflection tables", file=sys.stderr)
        db.executemany(
            "INSERT INTO meta VALUES (?, ?)",
            [
                ("language", code),
                ("source", "English Wiktionary via kaikki.org (Wiktextract)"),
                ("license", "CC BY-SA 4.0; text from Wiktionary contributors"),
                ("frequency", "Universal Dependencies: " + (", ".join(f"UD_{t}" for t in treebanks[code]) or "none")),
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
        built[code] = {
            "file": f"{code}.db.gz",
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            **stats[code],
        }
        print(f"{code}: {stats[code]['entries']:,} entries, {stats[code]['with_ipa']:,} with IPA, "
              f"{len(data) / 1e6:.1f} MB", file=sys.stderr)

    with open(os.path.join(args.out, "languages.json"), "w", encoding="utf-8") as f:
        json.dump(built, f, indent=2, sort_keys=True)


if __name__ == "__main__":
    main()
