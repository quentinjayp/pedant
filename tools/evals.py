"""
Compares models at the app's translation steps: each model translates the same sentences, told
exactly what the app tells Gemma (assets/instructions.json), and the results are scored against
translations made by people.

Two kinds of run:
- English, the learner's own language today: the learner's line translated from English into each
  of the app's languages (the Evals workflow).
- Other own languages (--own), for when the learner's own language can be another: the learner's
  line translated from it into each other language, and the tutor's reply translated from each
  other language back into it (the Evals (other own languages) workflow). The app's instructions
  are filled in with the own language, as the app does.

    python3 tools/evals.py references <fleurs dir>            # writes evals/references.json
    python3 tools/evals.py translate --model m.litertlm --name NAME [--own fr] --out outputs/NAME.json
    python3 tools/evals.py config <outputs dir> <work dir>     # writes promptfoo's config and tests
    python3 tools/evals.py summary <outputs dir>               # tables of scores, as Markdown

Each translation is keyed "<sentence id>|<kind>|<from>><to>", where kind is "line" (the learner's
own line into the language learned) or "reply" (the tutor's reply back into the learner's own).

The sentences are in evals/references.json (from FLEURS, Google's read-aloud recordings of the
FLORES sentences, CC-BY 4.0: the text people translated and read) and evals/own_phrases.json
(written by hand; the same shape). The models compared are in evals/models.json. The Evals
workflow runs all of this and never blocks a build; promptfoo makes the side-by-side report.

Scores are chrF (0 to 100): how many of the reference translation's runs of letters the model's
translation shares. It suits languages with many word endings, and Chinese, better than counting
whole words. A good translation worded differently from the reference still scores lower, so
scores compare models with each other rather than measure a translation outright.
"""

import argparse
import glob
import json
import os
import time

from app_languages import instruction as fill, languages
from evals_promptfoo import answer, labelled, parts, replied_labelled, score

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
INSTRUCTIONS = f"{ROOT}/app/src/main/assets/instructions.json"
REFERENCES = f"{ROOT}/evals/references.json"
OWN = f"{ROOT}/evals/own_phrases.json"
MODELS = f"{ROOT}/evals/models.json"

# FLEURS's name for each of the app's languages.
FLEURS = {
    "cs": "cs_cz", "da": "da_dk", "de": "de_de", "el": "el_gr", "es": "es_419", "fi": "fi_fi", "fr": "fr_fr",
    "hi": "hi_in", "hu": "hu_hu", "id": "id_id", "it": "it_it", "nl": "nl_nl", "no": "nb_no", "pl": "pl_pl",
    "pt": "pt_br", "ro": "ro_ro", "ru": "ru_ru", "sk": "sk_sk", "sl": "sl_si", "sr": "sr_rs", "sv": "sv_se",
    "sw": "sw_ke", "tr": "tr_tr", "uk": "uk_ua", "vi": "vi_vn", "zh": "cmn_hans_cn",
}
# How many sentences, and how long (in English words): short, like what's said in a conversation.
SENTENCES = 20
MIN_WORDS, MAX_WORDS = 5, 14


def sentences():
    """Every sentence to translate, with its reference translations: [{id, en, translations}]."""
    out = []
    for path in (REFERENCES, OWN):
        out += json.load(open(path, encoding="utf-8"))["sentences"]
    return out


def targets():
    """The app's languages to translate into (English is what's translated from): [(code, name)]."""
    return [(lang.code, lang.name) for lang in languages() if lang.code != "en"]


def names():
    """Every app language's English name, by code."""
    return {lang.code: lang.name for lang in languages()}


def text_in(sentence, code):
    """[sentence] in the language [code], as people wrote it, or None if it isn't in that language."""
    return sentence["en"] if code == "en" else sentence["translations"].get(code)


def jobs(own):
    """The translations of a run for a learner whose own language is [own]: (key, sentence, kind,
    from code, to code). English: the learner's line into each other language. Another language:
    the line into each other language, and each other language's reply back into [own]."""
    out = []
    for s in sentences():
        for other in names():
            if other == own or text_in(s, own) is None or text_in(s, other) is None:
                continue
            out.append((f"{s['id']}|line|{own}>{other}", s, "line", own, other))
            if own != "en":
                out.append((f"{s['id']}|reply|{other}>{own}", s, "reply", other, own))
    return out


def instruction(ins, kind, source, target):
    """What the app tells Gemma for a translation from [source] into [target] (codes)."""
    n = names()
    own = source if kind == "line" else target
    learned = target if kind == "line" else source
    text = ins["translate"] if kind == "line" else ins["replyTranslate"]
    return fill(text, n[learned], n[own]).replace("{register}", "formal")


def references(fleurs):
    """Picks the sentences FLEURS has in English and in every one of the app's languages."""
    def texts(code):
        found = {}
        for split in ("dev", "test"):
            for line in open(os.path.join(fleurs, f"{code}.{split}.tsv"), encoding="utf-8"):
                cols = line.rstrip("\n").split("\t")
                if len(cols) >= 3:
                    found.setdefault(cols[0], cols[2].strip())
        return found

    english = texts("en_us")
    by_lang = {code: texts(FLEURS[code]) for code, _ in targets()}
    ids = [i for i in english if all(i in t for t in by_lang.values())]
    ids = [i for i in ids if MIN_WORDS <= len(english[i].split()) <= MAX_WORDS and not any(c.isdigit() for c in english[i])]
    ids = sorted(ids, key=int)[:SENTENCES]
    data = {
        "_about": "English sentences with translations made by people, for tools/evals.py. From FLEURS (Google, CC-BY 4.0), "
                  "the read-aloud recordings of the FLORES sentences; written by tools/evals.py references.",
        "sentences": [{"id": f"fleurs-{i}", "en": english[i], "translations": {c: by_lang[c][i] for c in by_lang}} for i in ids],
    }
    with open(REFERENCES, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{len(ids)} sentences in {len(by_lang)} languages")


def text_of(message):
    content = message.get("content") if isinstance(message, dict) else message
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(c.get("text", "") for c in content if isinstance(c, dict))
    return str(content)


def translate(args):
    import litert_lm

    ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
    todo = [j for i, j in enumerate(jobs(args.own)) if i % args.shards == args.shard]
    if args.limit:
        todo = todo[:args.limit]
    print(f"{args.name}, own language {args.own}: {len(todo)} translations", flush=True)
    # The app's settings for a translation: CPU, and always the likeliest next word.
    exact = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)
    outputs = {}

    # Loaded once, holding a conversation per translation, as the app does.
    engine = litert_lm.Engine(args.model, backend=litert_lm.Backend.CPU(), max_num_tokens=2048)
    try:
        for key, s, kind, source, target in todo:
            system = instruction(ins, kind, source, target)
            started = time.time()
            try:
                with engine.create_conversation(system_message=system, sampler_config=exact) as conv:
                    raw = text_of(conv.send_message(text_in(s, source)))
            except Exception as e:  # one failure shouldn't lose the run
                raw = f"(failed: {e})"
            seconds = round(time.time() - started, 1)
            got = answer(kind, raw)
            outputs[key] = {"raw": raw, "seconds": seconds}
            print(f"{kind} {source}>{target} {s['id']} ({seconds} s): {got!r}" + ("" if got else f"  RAW {raw[:200]!r}"), flush=True)
    finally:
        engine.__exit__(None, None, None)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump({"model": args.name, "outputs": outputs}, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def load_outputs(folder):
    """{model name: {key: output}} from every outputs file (and shard) in [folder]. Keys from runs
    before there were other own languages ("<id>|<code>") are read as English lines."""
    found = {}
    for path in sorted(glob.glob(os.path.join(folder, "**", "*.json"), recursive=True)):
        data = json.load(open(path, encoding="utf-8"))
        if isinstance(data, dict) and "outputs" in data:
            outputs = {(k if k.count("|") == 2 else k.replace("|", "|line|en>")): v for k, v in data["outputs"].items()}
            found.setdefault(data["model"], {}).update(outputs)
    return found


def config(outputs_dir, work):
    """Writes promptfoo's config: a column per model (its translations, looked up), a row per
    sentence and language, each scored against the reference."""
    here = os.path.dirname(os.path.abspath(__file__))
    # A full path: promptfoo is run from inside the work folder.
    merged = os.path.abspath(os.path.join(work, "outputs.json"))
    os.makedirs(work, exist_ok=True)
    outputs = load_outputs(outputs_dir)
    # A column for each model that translated something in this run.
    models = [m["name"] for m in json.load(open(MODELS, encoding="utf-8"))["models"] if m["name"] in outputs]
    json.dump(outputs, open(merged, "w", encoding="utf-8"), ensure_ascii=False)
    n, by_id = names(), {s["id"]: s for s in sentences()}
    keys = sorted({k for found in outputs.values() for k in found})
    tests = []
    for key in keys:
        sentence, kind, source, target = parts(key)
        s = by_id.get(sentence)
        if s is None or text_in(s, target) is None:
            continue
        what = "Your line" if kind == "line" else "Tutor's reply"
        tests.append({
            "description": f"{what}, {n[source]} to {n[target]}: {text_in(s, source)}",
            "vars": {"key": key, "source": n[source], "target": n[target], "text": text_in(s, source),
                     "reference": text_in(s, target)},
        })
    cfg = {
        "description": "Pedant: translations between the app's languages, scored against people's translations",
        "prompts": ["Translate from {{source}} into {{target}}: {{text}}"],
        "providers": [
            {"id": f"file://{here}/evals_promptfoo.py:lookup", "label": m, "config": {"outputs": merged, "model": m}} for m in models
        ],
        "defaultTest": {"assert": [{"type": "python", "value": f"file://{here}/evals_promptfoo.py:chrf"}]},
        "tests": tests,
    }
    json.dump(cfg, open(os.path.join(work, "promptfooconfig.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{len(tests)} tests, {len(models)} models")


def scored(outputs):
    """Every translation's score: [(model, kind, from, to, chrF, seconds, whether it had its label)]."""
    by_id = {s["id"]: s for s in sentences()}
    rows = []
    for model, found in outputs.items():
        for key, out in found.items():
            sentence, kind, source, target = parts(key)
            s = by_id.get(sentence)
            reference = text_in(s, target) if s else None
            if reference is None:
                continue
            had_label = bool(labelled(out["raw"]) if kind == "line" else replied_labelled(out["raw"]))
            rows.append((model, kind, source, target, score(answer(kind, out["raw"]), reference, target), out["seconds"], had_label))
    return rows


def mean(xs):
    return f"{sum(xs) / len(xs):.1f}" if xs else "–"


def summary(outputs_dir):
    """Prints, as Markdown, each model's average scores: for English, by language learned; for the
    other own languages, by own language and kind, then by pair of languages."""
    outputs = load_outputs(outputs_dir)
    models = [m["name"] for m in json.load(open(MODELS, encoding="utf-8"))["models"] if m["name"] in outputs]
    if not models:
        print("No translations to score.")
        return
    n = names()
    rows = scored({m: outputs[m] for m in models})
    print("# Translation scores (chrF, 0 to 100; higher is closer to people's translations)\n")
    print("A good translation worded differently from the reference scores lower, so the scores compare the models "
          "with each other.\n")

    english = [r for r in rows if r[1] == "line" and r[2] == "en"]
    if english:
        print("## Own language English: your line into each language\n")
        print("| Language | " + " | ".join(models) + " |")
        print("|---|" + "---:|" * len(models))
        print("| **All languages** | " + " | ".join(f"**{mean([r[4] for r in english if r[0] == m])}**" for m in models) + " |")
        for code, name in targets():
            print(f"| {name} | " + " | ".join(mean([r[4] for r in english if r[0] == m and r[3] == code]) for m in models) + " |")
        print("| Seconds per translation (on the CI machine) | " + " | ".join(mean([r[5] for r in english if r[0] == m]) for m in models) + " |")
        print("| Answers without the SAY: label (the app takes the whole answer as the phrase) | "
              + " | ".join(str(sum(1 for r in english if r[0] == m and not r[6])) for m in models) + " |")
        print()

    others = [r for r in rows if not (r[1] == "line" and r[2] == "en")]
    owns = sorted({r[2] if r[1] == "line" else r[3] for r in others}, key=lambda c: n[c])
    if not owns:
        return
    own_of = lambda r: r[2] if r[1] == "line" else r[3]
    print("## Other own languages\n")
    print("**Your line**: what you say in your own language, translated into the language you're learning. "
          "**Tutor's reply**: the tutor's reply, translated from the language you're learning into your own.\n")
    head = [f"{m}: {what}" for m in models for what in ("your line", "tutor's reply")]
    print("| Own language | " + " | ".join(head) + " |")
    print("|---|" + "---:|" * len(head))
    for own in owns:
        cells = [mean([r[4] for r in others if r[0] == m and r[1] == k and own_of(r) == own]) for m in models for k in ("line", "reply")]
        print(f"| {n[own]} | " + " | ".join(cells) + " |")
    print("| **All** | " + " | ".join(f"**{mean([r[4] for r in others if r[0] == m and r[1] == k])}**" for m in models for k in ("line", "reply")) + " |")
    print("| Seconds per translation (on the CI machine) | "
          + " | ".join(mean([r[5] for r in others if r[0] == m and r[1] == k]) for m in models for k in ("line", "reply")) + " |")
    print("| Answers without their label (SAY: or TRANSLATION:), which the app takes whole | "
          + " | ".join(str(sum(1 for r in others if r[0] == m and r[1] == k and not r[6])) for m in models for k in ("line", "reply")) + " |")
    print()
    for m in models:
        for kind, title in (("line", "your line, from your own language into the one learned"),
                            ("reply", "the tutor's reply, from the language learned into your own")):
            print(f"### {m}: {title}\n")
            print("| Language learned | " + " | ".join(f"own {n[o]}" for o in owns) + " |")
            print("|---|" + "---:|" * len(owns))
            learned_of = lambda r: r[3] if r[1] == "line" else r[2]
            for code in sorted({learned_of(r) for r in others}, key=lambda c: n[c]):
                cells = [mean([r[4] for r in others if r[0] == m and r[1] == kind and own_of(r) == o and learned_of(r) == code]) for o in owns]
                print(f"| {n[code]} | " + " | ".join(cells) + " |")
            print()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    r = sub.add_parser("references")
    r.add_argument("fleurs", help="a folder of FLEURS's <code>.dev.tsv and <code>.test.tsv files")
    t = sub.add_parser("translate")
    t.add_argument("--model", required=True)
    t.add_argument("--name", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--shard", type=int, default=0)
    t.add_argument("--shards", type=int, default=1)
    t.add_argument("--limit", type=int, default=0, help="only the first N translations, to try a model out")
    t.add_argument("--own", default="en", help="the learner's own language: en (as today), or another app language's code")
    c = sub.add_parser("config")
    c.add_argument("outputs")
    c.add_argument("work")
    s = sub.add_parser("summary")
    s.add_argument("outputs")
    a = ap.parse_args()
    if a.command == "references":
        references(a.fleurs)
    elif a.command == "translate":
        translate(a)
    elif a.command == "config":
        config(a.outputs, a.work)
    else:
        summary(a.outputs)
