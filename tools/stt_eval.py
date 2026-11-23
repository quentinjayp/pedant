"""A one-off eval: how well Gemma 4 does speech recognition, against Omnilingual (the app's
recognizer), in the app's languages. Run by .github/workflows/stt-eval.yml, on demand only. It
reports once, to help decide whether Free talk should use a different recognizer; it isn't one of
the permanent evals.

Three tests:
- Accuracy: FLEURS recordings (Google, CC-BY 4.0) of people reading short sentences, scored by word
  error rate against what they read.
- Faithfulness: sentences with one wrong article ("le voiture"), read by the language's Piper voice,
  in the languages whose articles show gender. Does the recognizer write the mistake as said, or
  correct it?
- Long speech: about a minute of FLEURS recordings joined together, as a talk would be.

Gemma is asked two ways: plainly ("Transcribe this audio"), and told to keep mistakes (verbatim).

    python3 tools/stt_eval.py prepare --fleurs DIR --voices DIR --out clips
    python3 tools/stt_eval.py run --recognizer omnilingual --model DIR --clips clips --out out.json
    python3 tools/stt_eval.py run --recognizer gemma --model FILE --name NAME --clips clips --out out.json
    python3 tools/stt_eval.py report out1.json out2.json ...
"""

import argparse
import difflib
import glob
import json
import os
import random
import resource
import re
import time
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANGUAGES = f"{ROOT}/app/src/main/assets/languages.json"

# FLEURS's name for each of the app's languages.
FLEURS = {
    "cs": "cs_cz", "da": "da_dk", "de": "de_de", "el": "el_gr", "en": "en_us", "es": "es_419", "fi": "fi_fi",
    "fr": "fr_fr", "hi": "hi_in", "hu": "hu_hu", "id": "id_id", "it": "it_it", "nl": "nl_nl", "no": "nb_no",
    "pl": "pl_pl", "pt": "pt_br", "ro": "ro_ro", "ru": "ru_ru", "sk": "sk_sk", "sl": "sl_si", "sr": "sr_rs",
    "sv": "sv_se", "sw": "sw_ke", "tr": "tr_tr", "uk": "uk_ua",
}
ACCURACY_CLIPS = 15
FAITHFUL_CLIPS = 8
MIN_WORDS, MAX_WORDS = 5, 14
LONG_SECONDS = 60
RATE = 16000

PROMPTS = {
    "plain": "Transcribe this {language} audio. Reply with the transcript only.",
    "verbatim": "Transcribe exactly what is said in this {language} audio, word for word, keeping any "
                "mistakes in grammar or word choice. Do not correct anything. Reply with the transcript only.",
}


def app_languages():
    return json.load(open(LANGUAGES, encoding="utf-8"))["languages"]


# Serbian: FLEURS writes it in Cyrillic and the app in Latin, so both sides are compared in Latin.
SERBIAN = dict(zip("абвгдђежзијклљмнњопрстћуфхцчџш",
                   ["a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m", "n", "nj", "o",
                    "p", "r", "s", "t", "ć", "u", "f", "h", "c", "č", "dž", "š"]))


def words(text, code):
    """[text] as words to compare: lower case, without punctuation, Serbian in Latin."""
    text = unicodedata.normalize("NFC", text).lower()
    if code == "sr":
        text = "".join(SERBIAN.get(c, c) for c in text)
    text = "".join(c if c.isalnum() or c.isspace() or c in "'’-" else " " for c in text)
    return [w.strip("'’-") for w in text.split() if w.strip("'’-")]


def edits(a, b):
    """The word-level edit distance between lists [a] and [b]."""
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


# ---- prepare ----

def read_tsv(d, split):
    out = []
    for line in open(os.path.join(d, f"{split}.tsv"), encoding="utf-8"):
        cols = line.rstrip("\n").split("\t")
        if len(cols) >= 3:
            out.append((cols[1], cols[2].strip()))
    return out


def load_wav(path):
    import soundfile as sf
    import numpy as np
    data, rate = sf.read(path, dtype="float32", always_2d=True)
    x = data[:, 0]
    if rate != RATE:
        n = int(len(x) * RATE / rate)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype("float32")
    return x


def save_wav(path, x):
    import soundfile as sf
    sf.write(path, x, RATE, subtype="PCM_16")


def piper(voices, voice):
    import sherpa_onnx
    d = f"{voices}/vits-piper-{voice}"
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=glob.glob(f"{d}/*.onnx")[0], tokens=f"{d}/tokens.txt",
                                                   data_dir=f"{d}/espeak-ng-data"),
        num_threads=4))
    return sherpa_onnx.OfflineTts(cfg)


def prepare(args):
    """Picks the clips for every test, and writes them with clips.json."""
    import numpy as np
    os.makedirs(args.out, exist_ok=True)
    rng = random.Random(7)
    clips = []
    for lang in app_languages():
        code = lang["code"]
        d = os.path.join(args.fleurs, FLEURS[code])
        if not os.path.isdir(d):
            print(f"{code}: no FLEURS data")
            continue
        wavs = {os.path.basename(p): p for p in glob.glob(f"{d}/**/*.wav", recursive=True)}
        rows, seen = [], set()
        for file, text in read_tsv(d, "dev"):
            if file in wavs and text not in seen and not any(c.isdigit() for c in text):
                seen.add(text)
                rows.append((wavs[file], text))
        short = [r for r in rows if MIN_WORDS <= len(r[1].split()) <= MAX_WORDS][:ACCURACY_CLIPS]
        for i, (path, text) in enumerate(short):
            name = f"{code}-accuracy-{i}.wav"
            save_wav(os.path.join(args.out, name), load_wav(path))
            clips.append({"lang": code, "test": "accuracy", "file": name, "text": text})
        # Long speech: recordings after the short ones, joined with a short pause, to about a minute.
        parts, texts, length = [], [], 0
        for path, text in (r for r in rows if r not in short):
            x = load_wav(path)
            parts += [x, np.zeros(RATE // 2, dtype="float32")]
            texts.append(text)
            length += len(x) / RATE + 0.5
            if length >= LONG_SECONDS:
                break
        name = f"{code}-long.wav"
        save_wav(os.path.join(args.out, name), np.concatenate(parts))
        clips.append({"lang": code, "test": "long", "file": name, "text": " ".join(texts), "seconds": round(length)})
        # Faithfulness: one article swapped for another, read by the language's voice.
        articles = lang.get("articles")
        if articles:
            tts = piper(args.voices, lang["piperVoice"])
            n = 0
            for _, text in rows:
                ws = text.split()
                spots = [k for k, w in enumerate(ws) if w.lower() in articles]
                if not spots or not MIN_WORDS <= len(ws) <= MAX_WORDS:
                    continue
                k = rng.choice(spots)
                right = ws[k].lower()
                wrong = rng.choice([a for a in articles if a != right])
                said = ws[:k] + [wrong if ws[k].islower() else wrong.capitalize()] + ws[k + 1:]
                audio = tts.generate(" ".join(said), sid=0, speed=1.0)
                x = np.array(audio.samples, dtype="float32")
                x = np.interp(np.linspace(0, len(x) - 1, int(len(x) * RATE / audio.sample_rate)), np.arange(len(x)), x)
                name = f"{code}-faithful-{n}.wav"
                save_wav(os.path.join(args.out, name), x.astype("float32"))
                clips.append({"lang": code, "test": "faithful", "file": name, "text": " ".join(said),
                              "index": k, "right": right, "wrong": wrong})
                n += 1
                if n >= FAITHFUL_CLIPS:
                    break
        print(f"{code}: {sum(c['lang'] == code for c in clips)} clips", flush=True)
    json.dump({"clips": clips}, open(os.path.join(args.out, "clips.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)


# ---- run ----

def omnilingual(model_dir):
    import sherpa_onnx
    d = glob.glob(f"{model_dir}/sherpa-onnx-omnilingual-*")[0]
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=f"{d}/model.int8.onnx", tokens=f"{d}/tokens.txt", num_threads=4)

    def hear(path, language, prompt):
        s = rec.create_stream()
        s.accept_waveform(RATE, load_wav(path))
        rec.decode_stream(s)
        return s.result.text

    return hear, [None]


def gemma(path):
    import litert_lm
    engine = litert_lm.Engine(path, backend=litert_lm.Backend.CPU(), audio_backend=litert_lm.Backend.CPU(),
                              max_num_tokens=4096)
    exact = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)

    def hear(file, language, prompt):
        message = {"role": "user", "content": [
            {"type": "audio", "path": os.path.abspath(file)},
            {"type": "text", "text": PROMPTS[prompt].format(language=language)},
        ]}
        with engine.create_conversation(sampler_config=exact) as conv:
            reply = conv.send_message(message)
        content = reply.get("content")
        if isinstance(content, list):
            return "".join(c.get("text", "") for c in content if isinstance(c, dict)).strip()
        return str(content).strip()

    return hear, list(PROMPTS)


def run(args):
    names = {l["code"]: l["englishName"] for l in app_languages()}
    clips = json.load(open(os.path.join(args.clips, "clips.json"), encoding="utf-8"))["clips"]
    if args.only:
        keep = set(args.only.split(","))
        clips = [c for c in clips if c["lang"] in keep]
    hear, prompts = omnilingual(args.model) if args.recognizer == "omnilingual" else gemma(args.model)
    results = []
    for c in clips:
        for prompt in prompts:
            start = time.time()
            try:
                heard, error = hear(os.path.join(args.clips, c["file"]), names[c["lang"]], prompt), None
            except Exception as e:  # A clip the recognizer can't take is reported, not fatal.
                heard, error = "", f"{type(e).__name__}: {e}"
            results.append({**c, "prompt": prompt, "heard": heard, "error": error,
                            "seconds_taken": round(time.time() - start, 2)})
            print(f"{c['file']} {prompt or ''}: {heard[:100]}{' ERROR ' + error if error else ''}", flush=True)
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss // 1024
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump({"name": args.name, "peak_mb": peak, "results": results},
              open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


# ---- report ----

def faithful_verdict(r):
    """Whether the recognizer wrote the wrong article as said: kept, corrected, or neither."""
    said = words(r["text"], r["lang"])
    heard = words(r["heard"], r["lang"])
    target = len(words(" ".join(r["text"].split()[:r["index"]]), r["lang"]))
    m = difflib.SequenceMatcher(a=said, b=heard, autojunk=False)
    for tag, i1, i2, j1, j2 in m.get_opcodes():
        if i1 <= target < i2:
            got = heard[j1 + (target - i1)] if tag in ("equal", "replace") and j1 + (target - i1) < j2 else None
            if got == r["wrong"]:
                return "kept"
            if got == r["right"]:
                return "corrected"
            return "other"
    return "other"


def label(run_name, prompt):
    return f"{run_name} ({prompt})" if prompt else run_name


def report(args):
    runs = [json.load(open(p, encoding="utf-8")) for p in args.files]
    lines = ["# Speech recognition eval", ""]
    cols = [(r, p) for r in runs for p in sorted({x["prompt"] for x in r["results"]}, key=lambda p: p or "")]
    head = "| Language | " + " | ".join(label(r["name"], p) for r, p in cols) + " |"
    rule = "|---|" + "---|" * len(cols)

    def rate(r, prompt, lang, test):
        rows = [x for x in r["results"] if x["prompt"] == prompt and x["lang"] == lang and x["test"] == test]
        if not rows:
            return None
        errs = sum(edits(words(x["text"], lang), words(x["heard"], lang)) for x in rows)
        total = sum(len(words(x["text"], lang)) for x in rows)
        return 100 * errs / max(total, 1)

    langs = sorted({x["lang"] for r in runs for x in r["results"]})
    for test, title in (("accuracy", "Accuracy: word error rate, % (lower is better)"),
                        ("long", "Long speech, about a minute: word error rate, %")):
        lines += [f"## {title}", "", head, rule]
        sums = [[] for _ in cols]
        for lang in langs:
            cells = []
            for i, (r, p) in enumerate(cols):
                v = rate(r, p, lang, test)
                if v is not None:
                    sums[i].append(v)
                cells.append("" if v is None else f"{v:.0f}")
            lines.append(f"| {lang} | " + " | ".join(cells) + " |")
        lines.append("| **Average** | " + " | ".join(f"**{sum(s) / len(s):.0f}**" if s else "" for s in sums) + " |")
        lines.append("")

    lines += ["## Faithfulness: a wrong article, kept as said or corrected", "",
              "| Language | " + " | ".join(label(r["name"], p) + " kept / corrected / other" for r, p in cols) + " |",
              rule]
    flangs = sorted({x["lang"] for r in runs for x in r["results"] if x["test"] == "faithful"})
    totals = [[0, 0, 0] for _ in cols]
    for lang in flangs:
        cells = []
        for i, (r, p) in enumerate(cols):
            v = [faithful_verdict(x) for x in r["results"] if x["prompt"] == p and x["lang"] == lang and x["test"] == "faithful"]
            k, c, o = v.count("kept"), v.count("corrected"), v.count("other")
            totals[i] = [totals[i][0] + k, totals[i][1] + c, totals[i][2] + o]
            cells.append(f"{k} / {c} / {o}")
        lines.append(f"| {lang} | " + " | ".join(cells) + " |")
    lines.append("| **All** | " + " | ".join(f"**{k} / {c} / {o}**" for k, c, o in totals) + " |")
    lines.append("")

    lines += ["## Time and memory (CI machine, not a phone)", "",
              "| Recognizer | Seconds per short clip | Seconds per long clip | Peak memory, MB | Errors |", "|---|---|---|---|---|"]
    for r, p in cols:
        rows = [x for x in r["results"] if x["prompt"] == p]
        short = [x["seconds_taken"] for x in rows if x["test"] != "long"]
        long_ = [x["seconds_taken"] for x in rows if x["test"] == "long"]
        errors = sum(1 for x in rows if x["error"])
        lines.append(f"| {label(r['name'], p)} | {sum(short) / max(len(short), 1):.1f} | "
                     f"{sum(long_) / max(len(long_), 1):.1f} | {r['peak_mb']} | {errors} |")
    lines.append("")
    lines.append("Faithfulness: \"other\" is a different word, or nothing, where the article was said.")
    text = "\n".join(lines) + "\n"
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(text)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("prepare")
    a.add_argument("--fleurs", required=True)
    a.add_argument("--voices", required=True)
    a.add_argument("--out", required=True)
    b = sub.add_parser("run")
    b.add_argument("--recognizer", choices=["omnilingual", "gemma"], required=True)
    b.add_argument("--model", required=True)
    b.add_argument("--name", default="Omnilingual")
    b.add_argument("--clips", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--only", help="comma-separated language codes")
    c = sub.add_parser("report")
    c.add_argument("files", nargs="+")
    args = p.parse_args()
    {"prepare": prepare, "run": run, "report": report}[args.cmd](args)


if __name__ == "__main__":
    main()
