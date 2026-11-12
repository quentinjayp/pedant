"""
Builds the app's word dictionaries from English Wiktionary, via kaikki.org's Wiktextract data.

Reads the raw data as a stream on stdin (decompressed on the fly, never saved), keeps entries in
the app's languages, and keeps only what the app uses: spelling, part of speech, IPA (the one
shown, and every pronunciation listed, tab-separated), a few short English meanings, and the word
it is a form of. Writes one gzipped SQLite file per
language, plus a manifest describing them.

A verb form ("eu", past participle of avoir) also gets its base word's meanings in that form
(form_meanings: "had; owned", not "to have; to own"), using English Wiktionary's English verb forms
from the same data.

Each entry also gets how common its part of speech is for that spelling (freq), counted in
Universal Dependencies treebanks, so the app can show "est" (is) before "est" (east). Only
treebanks whose license allows it (no NonCommercial, NoDerivatives or GPL) are counted; a language
with none keeps Wiktionary's order.

    python3 tools/build_dictionaries.py --list-treebanks   # the UD_* repositories to clone into ud/
    curl -sSL https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz | gzip -dc \
        | python3 tools/build_dictionaries.py --out out --ud ud --languages app/src/main/java/com/pedant/app/Languages.kt

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

# Universal Dependencies treebanks to count parts of speech in, per app language. Each one's
# license is checked when it's read (see ud_license_ok); Hungarian's only treebank is
# NonCommercial, so Hungarian has none.
UD_TREEBANKS = {
    "ar": ["Arabic-PUD"], "cs": ["Czech-CAC", "Czech-CLTT"], "da": ["Danish-DDT"],
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
    """A few short meanings, common senses first, the word this is a form of, if any, and that
    sense's tags (which form it is)."""
    kept, form_of, form_tags = [], None, []
    usable = [s for s in senses or [] if s.get("glosses")]
    preferred = [s for s in usable if not SKIP_SENSE_TAGS & set(s.get("tags") or [])] or usable
    for s in preferred:
        if form_of is None:
            for link in (s.get("form_of") or []) + (s.get("alt_of") or []):
                if link.get("word"):
                    form_of = link["word"]
                    form_tags = s.get("tags") or []
                    break
        gloss = s["glosses"][-1].strip()
        if len(gloss) > MAX_GLOSS:
            gloss = gloss[: MAX_GLOSS - 1].rstrip() + "…"
        if gloss and gloss not in kept:
            kept.append(gloss)
        if len(kept) >= MAX_SENSES:
            break
    return kept, form_of, form_tags


# ---- A verb form's meanings in that form ("eu": had, not to have) ------------------------------

# English verb forms worth reading off Wiktionary's English entries, by kind.
ENGLISH_FORM_KINDS = {
    "past": lambda t: "past" in t and "participle" not in t,
    "past_participle": lambda t: "past" in t and "participle" in t,
    "present_participle": lambda t: "present" in t and "participle" in t,
}
SKIP_FORM_TAGS = {"archaic", "obsolete", "dialectal", "nonstandard", "rare", "table-tags", "inflection-template",
                  "colloquial", "informal", "error-unknown-tag", "misspelling"}
# How many base meanings a form gets, as the app shows at most.
MAX_FORM_MEANINGS = 4


def english_verb_forms(entry, table):
    """Adds an English verb's past, past participle and present participle to [table]."""
    lemma = entry.get("word", "").lower()
    if not lemma or lemma in table:
        return
    found = {}
    for f in entry.get("forms") or []:
        form, tags = f.get("form"), set(f.get("tags") or [])
        if not form or " " in form or tags & SKIP_FORM_TAGS:
            continue
        for kind, matches in ENGLISH_FORM_KINDS.items():
            if kind not in found and matches(tags):
                found[kind] = form
    if found:
        table[lemma] = found


def form_tense(tags):
    """Which English form a verb form's meaning takes, from its Wiktionary tags; None to leave it."""
    t = set(tags)
    if "participle" in t and "past" in t:
        return "past_participle"
    if ("participle" in t and "present" in t) or "gerund" in t:
        return "present_participle"
    if "conditional" in t:
        return "conditional"
    if "future" in t:
        return "future"
    if t & {"past", "preterite", "imperfect", "historic", "pluperfect"}:
        return "past"
    return None


def english_form(verb, kind, table):
    """[verb] in the English form [kind], from Wiktionary's English entries or the regular rules."""
    known = table.get(verb.lower(), {}).get(kind)
    if known:
        return known
    if kind == "present_participle":
        if verb.endswith("ie"):
            return verb[:-2] + "ying"
        if verb.endswith("e") and not verb.endswith("ee"):
            return verb[:-1] + "ing"
        return verb + "ing"
    if verb.endswith("e"):
        return verb + "d"
    if re.search(r"[^aeiou]y$", verb):
        return verb[:-1] + "ied"
    return verb + "ed"


def inflect_meaning(gloss, tense, table):
    """An English meaning ("to have (an age)") in [tense] ("had (an age)"), or None if it isn't a
    "to ..." verb meaning. A second verb joined by "or"/"and" changes too ("to experience or suffer
    from" → "experienced or suffered from")."""
    m = re.match(r"to ([A-Za-z]+)(.*)", gloss, re.S)
    if not m:
        return None
    verb, tail = m.groups()
    if tense in ("future", "conditional"):
        return ("will " if tense == "future" else "would ") + verb + tail
    second = re.match(r"(,? (?:or|and) |, )([A-Za-z]+)(.*)", tail, re.S)
    if second and second.group(2).lower() in table:
        tail = second.group(1) + english_form(second.group(2), tense, table) + second.group(3)
    return english_form(verb, tense, table) + tail


def add_form_meanings(db, table):
    """Gives each verb form noted while reading its base word's meanings in that form."""
    pending = db.execute(
        "SELECT entries.rowid, entries.form_of, entries.pos, forms.tense FROM forms JOIN entries ON entries.rowid = forms.row"
    ).fetchall()
    updates = []
    for row, base, pos, tense in pending:
        bases = db.execute("SELECT pos, meanings FROM entries WHERE key = ? AND form_of IS NULL", (key_of(base),)).fetchall()
        same = [m for p, m in bases if p == pos] or [m for _, m in bases]
        meanings = []
        for block in same:
            for gloss in block.split("\n"):
                if gloss not in meanings:
                    meanings.append(gloss)
        meanings = meanings[:MAX_FORM_MEANINGS]
        inflected = [inflect_meaning(g, tense, table) for g in meanings]
        if any(inflected):
            updates.append(("\n".join(i or g for i, g in zip(inflected, meanings)), row))
    db.executemany("UPDATE entries SET form_meanings = ? WHERE rowid = ?", updates)
    db.execute("DROP TABLE forms")
    return len(updates)


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
                    k = (key_of(cols[1]), pos)
                    counts[k] = counts.get(k, 0) + 1
    return counts, used


def open_db(path):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute(
        "CREATE TABLE entries (key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT, ipa TEXT, ipas TEXT,"
        " meanings TEXT NOT NULL, form_of TEXT, freq INTEGER NOT NULL, form_meanings TEXT)"
    )
    # Verb forms whose meanings are put in their form once every entry is in; see add_form_meanings.
    db.execute("CREATE TABLE forms (row INTEGER NOT NULL, tense TEXT NOT NULL)")
    db.execute("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT)")
    return db


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    ap.add_argument("--languages", help="Languages.kt, to check every app language is covered")
    ap.add_argument("--ud", help="the folder the UD_* treebanks are cloned into")
    ap.add_argument("--list-treebanks", action="store_true", help="print the UD_* repositories to clone")
    args = ap.parse_args()
    if args.list_treebanks:
        print("\n".join(f"UD_{t}" for ts in UD_TREEBANKS.values() for t in ts))
        return
    if not args.out or not args.languages:
        ap.error("--out and --languages are required")

    codes = app_codes(args.languages)
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

    english_forms = {}  # English verb -> its past, past participle and present participle
    lines = 0
    for line in sys.stdin.buffer:
        lines += 1
        if lines % 1_000_000 == 0:
            print(f"{lines:,} lines read", file=sys.stderr, flush=True)
        try:
            entry = loads(line)
        except ValueError:
            continue
        if entry.get("lang") == "English" and entry.get("pos") == "verb":
            english_verb_forms(entry, english_forms)
        code = by_name.get(entry.get("lang"))
        word = entry.get("word")
        if code is None or not word:
            continue
        meanings, form_of, form_tags = pick_senses(entry.get("senses"))
        if not meanings:
            continue
        key = key_of(word)
        if rows[code].get(key, 0) >= MAX_ROWS_PER_WORD:
            continue
        rows[code][key] = rows[code].get(key, 0) + 1
        ipa, variants = pick_ipa(entry.get("sounds"), code)
        cur = dbs[code].execute(
            "INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
            (key, word, entry.get("pos"), ipa, "\t".join(variants) or None, "\n".join(meanings), form_of,
             freq[code].get((key, entry.get("pos")), 0)),
        )
        tense = form_tense(form_tags) if form_of and entry.get("pos") == "verb" else None
        if tense:
            dbs[code].execute("INSERT INTO forms VALUES (?, ?)", (cur.lastrowid, tense))
        stats[code]["entries"] += 1
        stats[code]["with_ipa"] += ipa is not None

    print(f"{lines:,} lines read in total; {len(english_forms):,} English verbs' forms", file=sys.stderr)
    for verb in ("have", "be", "go", "suffer"):
        print(f"  {verb}: {english_forms.get(verb)}", file=sys.stderr)
    languages = {}
    for code, db in dbs.items():
        db.execute("CREATE INDEX entries_key ON entries (key)")
        print(f"{code}: {add_form_meanings(db, english_forms):,} verb forms given meanings in their form", file=sys.stderr)
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
