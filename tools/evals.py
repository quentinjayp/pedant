"""
Compares models at the app's translation step: each model translates the same English sentences
into the app's languages, told exactly what the app tells Gemma (assets/instructions.json), and
the results are scored against translations made by people.

    python3 tools/evals.py references <fleurs dir>            # writes evals/references.json
    python3 tools/evals.py translate --model m.litertlm --name NAME --out outputs/NAME.json
    python3 tools/evals.py config <outputs dir> <work dir>     # writes promptfoo's config and tests
    python3 tools/evals.py summary <outputs dir>               # a table of scores, as Markdown

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
import re
import time

from app_languages import languages

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


def said(raw):
    """The phrase in a model's answer, as the app reads it (Tutor.parse): the text after SAY:, up
    to the next label. Reasoning a model writes out first (<think>...</think>) isn't part of it."""
    raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S)
    m = re.search(r"SAY:(.*?)(?=\n?(?:REPLY|ENGLISH|SAY):|\Z)", raw, re.S)
    return m.group(1).strip() if m else ""


def translate(args):
    import litert_lm

    ins = json.load(open(INSTRUCTIONS, encoding="utf-8"))
    jobs = [(s, code, name) for s in sentences() for code, name in targets() if code in s["translations"]]
    jobs = [j for i, j in enumerate(jobs) if i % args.shards == args.shard]
    if args.limit:
        jobs = jobs[:args.limit]
    print(f"{args.name}: {len(jobs)} translations", flush=True)
    # The app's settings for a translation: CPU, and always the likeliest next word.
    exact = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)
    outputs = {}

    def load():
        return litert_lm.Engine(args.model, backend=litert_lm.Backend.CPU(), max_num_tokens=2048)

    # The app loads a model once and holds many conversations with it. A model that only answers
    # its first conversation properly (--fresh-engine) is loaded again for each translation, which
    # isn't counted in its time.
    engine = None if args.fresh_engine else load()
    try:
        for s, code, name in jobs:
            system = ins["translate"].replace("{language}", name).replace("{register}", "formal")
            one = load() if args.fresh_engine else engine
            started = time.time()
            try:
                with one.create_conversation(system_message=system, sampler_config=exact) as conv:
                    raw = text_of(conv.send_message(s["en"]))
            except Exception as e:  # one failure shouldn't lose the run
                raw = f"(failed: {e})"
            seconds = round(time.time() - started, 1)
            if args.fresh_engine:
                one.__exit__(None, None, None)
            outputs[f"{s['id']}|{code}"] = {"raw": raw, "say": said(raw), "seconds": seconds}
            print(f"{code} {s['id']} ({seconds} s): {said(raw)!r}" + ("" if said(raw) else f"  RAW {raw[:200]!r}"), flush=True)
    finally:
        if engine is not None:
            engine.__exit__(None, None, None)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump({"model": args.name, "outputs": outputs}, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def load_outputs(folder):
    """{model name: {"id|code": output}} from every outputs file (and shard) in [folder]."""
    found = {}
    for path in sorted(glob.glob(os.path.join(folder, "**", "*.json"), recursive=True)):
        data = json.load(open(path, encoding="utf-8"))
        if isinstance(data, dict) and "outputs" in data:
            found.setdefault(data["model"], {}).update(data["outputs"])
    return found


def config(outputs_dir, work):
    """Writes promptfoo's config: a column per model (its translations, looked up), a row per
    sentence and language, each scored against the reference."""
    here = os.path.dirname(os.path.abspath(__file__))
    names = [m["name"] for m in json.load(open(MODELS, encoding="utf-8"))["models"]]
    # A full path: promptfoo is run from inside the work folder.
    merged = os.path.abspath(os.path.join(work, "outputs.json"))
    os.makedirs(work, exist_ok=True)
    json.dump(load_outputs(outputs_dir), open(merged, "w", encoding="utf-8"), ensure_ascii=False)
    tests = [
        {"description": f"{name}: {s['en']}",
         "vars": {"key": f"{s['id']}|{code}", "language": name, "english": s["en"], "reference": s["translations"][code]}}
        for code, name in targets() for s in sentences() if code in s["translations"]
    ]
    cfg = {
        "description": "Pedant: translations into the app's languages, scored against people's translations",
        "prompts": ["Translate into {{language}}: {{english}}"],
        "providers": [
            {"id": f"file://{here}/evals_promptfoo.py:lookup", "label": n, "config": {"outputs": merged, "model": n}} for n in names
        ],
        "defaultTest": {"assert": [{"type": "python", "value": f"file://{here}/evals_promptfoo.py:chrf"}]},
        "tests": tests,
    }
    json.dump(cfg, open(os.path.join(work, "promptfooconfig.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{len(tests)} tests, {len(names)} models")


def summary(outputs_dir):
    """Prints, as Markdown, each model's average score by language, and how long it took."""
    import sacrebleu

    outputs = load_outputs(outputs_dir)
    names = [m["name"] for m in json.load(open(MODELS, encoding="utf-8"))["models"] if m["name"] in outputs]
    if not names:
        print("No translations to score.")
        return
    rows, totals, times, empty = [], {n: [] for n in names}, {n: [] for n in names}, {n: 0 for n in names}
    for code, name in targets():
        cells = []
        for n in names:
            scores = []
            for s in sentences():
                out = outputs[n].get(f"{s['id']}|{code}")
                if code not in s["translations"] or out is None:
                    continue
                scores.append(sacrebleu.sentence_chrf(out["say"], [s["translations"][code]]).score)
                times[n].append(out["seconds"])
                empty[n] += not out["say"]
            totals[n] += scores
            cells.append(f"{sum(scores) / len(scores):.1f}" if scores else "–")
        rows.append(f"| {name} | " + " | ".join(cells) + " |")
    mean = lambda xs: f"{sum(xs) / len(xs):.1f}" if xs else "–"
    print("## Translation scores (chrF, 0 to 100; higher is closer to people's translations)\n")
    print("| Language | " + " | ".join(names) + " |")
    print("|---|" + "---:|" * len(names))
    print("| **All languages** | " + " | ".join(f"**{mean(totals[n])}**" for n in names) + " |")
    print("\n".join(rows))
    print("| Seconds per translation (on the CI machine) | " + " | ".join(mean(times[n]) for n in names) + " |")
    print("| Translations with no phrase in the answer | " + " | ".join(str(empty[n]) for n in names) + " |")
    print(f"\n{len(totals[names[0]])} translations per model. A good translation worded differently from the reference "
          "scores lower, so the scores compare the models with each other.")


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
    t.add_argument("--fresh-engine", action="store_true", help="load the model again for each translation")
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
