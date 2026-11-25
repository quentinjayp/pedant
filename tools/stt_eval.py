"""A one-off eval: how well other speech recognizers do, against Omnilingual 300M (the app's
recognizer), in the app's languages. Run by .github/workflows/stt-eval.yml, on demand only. It
reports once, to help decide whether Free talk should use a different recognizer; it isn't one of
the permanent evals.

Three tests:
- Accuracy: FLEURS recordings (Google, CC-BY 4.0) of people reading short sentences, scored by word
  error rate against what they read.
- Faithfulness: sentences with one wrong article ("le voiture"), read by the language's Piper voice,
  in the languages whose articles show gender. Does the recognizer write the mistake as said, or
  correct it?
- Long speech: about a minute of FLEURS recordings joined together, given to the recognizer whole.
- Four minutes: about four minutes of FLEURS recordings joined together, as a long turn in Free talk
  would be, cut at the pauses found by a voice activity detector (Silero VAD, as sherpa-onnx runs
  it) into pieces of up to 20 seconds, given to the recognizer one at a time. The same sentences are
  also given one at a time, to show what the cutting costs.

The recognizers:
- omnilingual: an Omnilingual CTC model (300M, v1 or v2), as sherpa-onnx runs it. (1B v2 was tested
  and dropped: more accurate, but about three times slower than 300M on long speech.)
- omnilingual-dictionary: Omnilingual 300M, allowed to write only words in the language's dictionary
  (the app's, with inflected forms), each as likely as any other, so it can't prefer "la" to "le".
- parakeet: NVIDIA Parakeet TDT 0.6B v3, in the 19 of the app's languages it has.
- omnilingual-zipa: Omnilingual 300M's words, checked by sound with ZIPA (Zipa-Cr-large, the app's
  sound recognizer). ZIPA's sounds are lined up with espeak-ng's sounds for Omnilingual's words;
  then for each word, the dictionary words close to it in spelling (as for snapping "when close")
  are said by espeak-ng, and the one whose sounds are closest to what ZIPA heard for that word is
  written. A word that's in the dictionary is replaced only when another is clearly closer in sound
  (ZIPA_MARGIN); one that isn't is replaced by the closest dictionary word unless it's clearly
  farther. Sounds are compared with the app's rules (Matching.kt, as tools/calibrate_scoring.py
  mirrors them).
- gemma: Gemma 4 through LiteRT-LM, asked two ways: plainly ("Transcribe this audio"), and told to
  keep mistakes (verbatim).

Snapping, done afterwards on a recognizer's results: every word it wrote that isn't in the language's
dictionary is replaced by the dictionary word closest in spelling (the most common one, on a tie),
either always or only when it's close (see SNAP_CLOSE). Words that are in the dictionary are kept,
so a wrong article stays wrong.

    python3 tools/stt_eval.py prepare --fleurs DIR --voices DIR --out clips
    python3 tools/stt_eval.py run --recognizer omnilingual --model DIR --name NAME --clips clips --out out.json
    python3 tools/stt_eval.py run --recognizer omnilingual-dictionary --model DIR --dictionaries DIR ...
    python3 tools/stt_eval.py run --recognizer parakeet --model DIR ...
    python3 tools/stt_eval.py run --recognizer omnilingual-zipa --model DIR --zipa DIR --dictionaries DIR ...
    python3 tools/stt_eval.py run --recognizer gemma --model FILE ...
    python3 tools/stt_eval.py snap --dictionaries DIR --names "Omnilingual 300M,..." --out DIR out1.json ...
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
FOUR_MINUTES = 240
PIECE_SECONDS = 20
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
    text = unicodedata.normalize("NFC", text).lower().replace("’", "'")
    if code == "sr":
        text = "".join(SERBIAN.get(c, c) for c in text)
    text = "".join(c if c.isalnum() or c.isspace() or c in "'-" else " " for c in text)
    return [w.strip("'-") for w in text.split() if w.strip("'-")]


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
        # Long speech and four minutes: recordings after the short ones, each used once, joined with a
        # short pause.
        rest = iter([r for r in rows if r not in short])
        for test, seconds in (("long", LONG_SECONDS), ("four-minutes", FOUR_MINUTES)):
            parts, texts, length = [], [], 0
            for path, text in rest:
                x = load_wav(path)
                parts += [x, np.zeros(RATE // 2, dtype="float32")]
                texts.append(text)
                length += len(x) / RATE + 0.5
                if test == "four-minutes":  # Each sentence on its own too, to compare with.
                    name = f"{code}-four-minutes-{len(texts) - 1}.wav"
                    save_wav(os.path.join(args.out, name), x)
                    clips.append({"lang": code, "test": "four-minutes-sentences", "file": name, "text": text})
                if length >= seconds:
                    break
            name = f"{code}-{test}.wav"
            save_wav(os.path.join(args.out, name), np.concatenate(parts))
            clips.append({"lang": code, "test": test, "file": name, "text": " ".join(texts), "seconds": round(length)})
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
        model=glob.glob(f"{d}/model*.onnx")[0], tokens=f"{d}/tokens.txt", num_threads=4)

    def hear(path, code, language, prompt):
        s = rec.create_stream()
        s.accept_waveform(RATE, load_wav(path))
        rec.decode_stream(s)
        return s.result.text

    return hear, [None]


# NVIDIA Parakeet TDT 0.6B v3's languages that are also the app's.
PARAKEET = {"cs", "da", "de", "el", "en", "es", "fi", "fr", "hu", "it", "nl", "pl", "pt", "ro", "ru", "sk", "sl",
            "sv", "uk"}


def parakeet(model_dir):
    import sherpa_onnx
    d = glob.glob(f"{model_dir}/sherpa-onnx-nemo-parakeet-*")[0]
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx", joiner=f"{d}/joiner.int8.onnx",
        tokens=f"{d}/tokens.txt", model_type="nemo_transducer", num_threads=4)

    def hear(path, code, language, prompt):
        s = rec.create_stream()
        s.accept_waveform(RATE, load_wav(path))
        rec.decode_stream(s)
        return s.result.text

    return hear, [None]


def dictionary_words(db_path, vocab):
    """The dictionary's words (inflected forms included) that the recognizer can spell, sorted. Not
    affixes ("-ac") or phrases."""
    import sqlite3
    out = set()
    for (key,) in sqlite3.connect(db_path).execute("SELECT DISTINCT key FROM entries"):
        w = unicodedata.normalize("NFC", key.lower()).replace("’", "'")
        if w and " " not in w and w[0] != "-" and w[-1] != "-" and all(c in vocab for c in w):
            out.add(w)
    return sorted(out)


def lse(a, b):
    import math
    if a == -math.inf:
        return b
    if b == -math.inf:
        return a
    m = max(a, b)
    return m + math.log(math.exp(a - m) + math.exp(b - m))


def dictionary_decode(logp, tokens, lexicon, beam=10, top=10):
    """CTC prefix beam search that writes only [lexicon]'s words, with no language model: every word
    is as likely as any other, so only the sound decides between them. A word ending in an
    apostrophe ("l'") can run straight into the next, and words can be joined by hyphens."""
    import bisect
    import math
    import numpy as np
    memo = {}

    def kind(w):  # 0: no word starts so, 1: some word starts so, 2: a word
        if w not in memo:
            i = bisect.bisect_left(lexicon, w)
            memo[w] = 0 if i == len(lexicon) or not lexicon[i].startswith(w) else 2 if lexicon[i] == w else 1
        return memo[w]

    def extend(text, part, t):
        """The ways to add token [t] to [text], whose word so far is [part]: (text, new word so far)."""
        if t == " ":
            return [(text + " ", "")] if part and kind(part) == 2 else []
        out = [(text + t, part + t)] if kind(part + t) else []
        if part and kind(part) == 2:
            if t == "-":
                out.append((text + t, ""))
            elif part.endswith("'") and kind(t):
                out.append((text + t, t))
        return out

    inf = -math.inf
    beams = {("", ""): [0.0, inf, -1]}  # (text, word so far): [log p ending in blank, log p not, last token]
    for frame in logp:
        order = np.argsort(frame)[::-1]
        cands = [int(c) for c in order[:top + 4] if c > 3][:top]
        nxt = {}

        def get(key, last):
            if key not in nxt:
                nxt[key] = [inf, inf, last]
            return nxt[key]

        for (text, part), (pb, pnb, last) in beams.items():
            total = lse(pb, pnb)
            n = get((text, part), last)
            n[0] = lse(n[0], total + frame[0])
            for c in cands:
                lp = float(frame[c])
                if c == last:
                    n[1] = lse(n[1], pnb + lp)
                    score = pb + lp
                else:
                    score = total + lp
                for key in extend(text, part, tokens[c]):
                    m = get(key, c)
                    m[1] = lse(m[1], score)
        beams = dict(sorted(nxt.items(), key=lambda kv: -lse(kv[1][0], kv[1][1]))[:beam])
    # The likeliest that ends on a whole word; failing that, the likeliest without its unfinished word.
    ends = [(lse(pb, pnb), text) for (text, part), (pb, pnb, _) in beams.items()
            if (part == "" and not text.endswith("-")) or kind(part) == 2]
    if ends:
        return max(ends)[1].strip()
    score, text, part = max((lse(pb, pnb), text, part) for (text, part), (pb, pnb, _) in beams.items())
    return text[:len(text) - len(part)].strip(" -")


# ---- Omnilingual and ZIPA ----
# The sound comparison of app/src/main/java/com/pedant/app/Matching.kt, as tools/calibrate_scoring.py
# mirrors it.

CLOSE_SOUNDS = [
    {"i", "ɪ", "y", "ʏ", "j"},
    {"e", "ɛ", "ə", "ɘ", "œ", "ø", "ɜ", "æ"},
    {"a", "ɑ", "æ", "ɐ", "ʌ"},
    {"o", "ɔ", "ɒ", "ʌ", "ɤ"},
    {"u", "ʊ", "ɯ", "w"},
    {"r", "ʁ", "ɹ", "ʀ", "ɾ", "ɻ", "χ"},
    {"ʃ", "ʂ", "ɕ"},
    {"ʒ", "ʐ", "ʑ"},
    {"n", "ŋ", "ɲ"},
    {"l", "ɫ", "ʎ"},
]
VOICING = {(a, b) for a, b in [("p", "b"), ("t", "d"), ("k", "ɡ"), ("s", "z"), ("f", "v"), ("ʃ", "ʒ"), ("θ", "ð")]}
VOICING |= {(b, a) for a, b in VOICING}
IPA_DROP = set("/[].ˈˌ‿͜͡ -")
# How much closer in sound (per sound) another word must be to replace one in the dictionary, and how
# much farther a dictionary word may be and still replace one that isn't.
ZIPA_MARGIN = 0.15


def is_mark(s):
    return bool(s) and all(unicodedata.category(c) == "Mn" or c in "ˈˌːˑ˞ʰʲʷ" for c in s)


def sound_indel(s):
    return 0.3 if is_mark(s) else 1.0


def sound_cost(a, b):
    a, b = ("ɡ" if a == "g" else a), ("ɡ" if b == "g" else b)
    if a == b:
        return 0.0
    if is_mark(a) or is_mark(b):
        return 0.3
    if any(a in g and b in g for g in CLOSE_SOUNDS):
        return 0.35
    if (a, b) in VOICING:
        return 0.5
    return 1.0


def align(ref, hyp):
    """The cheapest alignment of sounds [ref] with [hyp]: (ref index or None, hyp index or None, cost)."""
    n, m = len(ref), len(hyp)
    d = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        d[i][0] = d[i - 1][0] + sound_indel(ref[i - 1])
    for j in range(1, m + 1):
        d[0][j] = d[0][j - 1] + sound_indel(hyp[j - 1])
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j - 1] + sound_cost(ref[i - 1], hyp[j - 1]),
                          d[i - 1][j] + sound_indel(ref[i - 1]), d[i][j - 1] + sound_indel(hyp[j - 1]))
    steps, i, j = [], n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + sound_cost(ref[i - 1], hyp[j - 1]):
            steps.append((i - 1, j - 1, sound_cost(ref[i - 1], hyp[j - 1]))); i -= 1; j -= 1
        elif i > 0 and d[i][j] == d[i - 1][j] + sound_indel(ref[i - 1]):
            steps.append((i - 1, None, sound_indel(ref[i - 1]))); i -= 1
        else:
            steps.append((None, j - 1, sound_indel(hyp[j - 1]))); j -= 1
    return steps[::-1]


def sound_distance(a, b):
    """How far sounds [b] are from [a], per sound."""
    return sum(c for _, _, c in align(a, b)) / max(len(a), len(b), 1)


def ipa_symbols(ipa):
    ipa = re.sub(r"\([^)]*\)", "", unicodedata.normalize("NFD", ipa))
    return [c for c in ipa if c not in IPA_DROP and not c.isspace() and not c.isdigit()]


class Espeak:
    """espeak-ng's sounds for words, one call for many, remembered."""

    def __init__(self, voice):
        self.voice, self.known = voice, {}

    def sounds(self, ws):
        import subprocess
        new = sorted({w for w in ws if w not in self.known})
        if new:
            out = subprocess.run(["espeak-ng", "-q", "--ipa", "-v", self.voice], input="\n".join(new) + "\n",
                                 capture_output=True, text=True).stdout.split("\n")
            for k, w in enumerate(new):
                self.known[w] = ipa_symbols(out[k]) if k < len(out) else []
        return [self.known[w] for w in ws]


def omnilingual_zipa(model_dir, zipa_dir, dictionaries, replace_known=True):
    import numpy as np
    import sherpa_onnx
    from rapidfuzz import process
    from rapidfuzz.distance import Levenshtein
    hear_words = omnilingual(model_dir)[0]
    zipa = sherpa_onnx.OfflineRecognizer.from_zipformer_ctc(
        model=f"{zipa_dir}/model.int8.onnx", tokens=f"{zipa_dir}/tokens.txt", num_threads=4)
    voices = {l["code"]: l["espeakVoice"] for l in app_languages()}
    loaded = {}

    def hear(path, code, language, prompt):
        if code not in loaded:
            loaded.clear()  # One language at a time.
            lexicon = dictionary_by_use(f"{dictionaries}/{code}.db")
            loaded[code] = (lexicon, set(lexicon), Espeak(voices[code]))
        lexicon, known, espeak = loaded[code]
        ws = words(hear_words(path, code, language, prompt), code)
        if not ws:
            return ""
        # ZIPA's sounds, with silence around them as the app gives it.
        x = load_wav(path)
        pad = np.zeros(RATE // 4, dtype="float32")
        x = np.concatenate([pad, x, pad, np.zeros(max(0, RATE - len(x) - 2 * len(pad)), dtype="float32")])
        s = zipa.create_stream()
        s.accept_waveform(RATE, x)
        zipa.decode_stream(s)
        heard = [t.strip() for t in s.result.tokens if t.strip()]
        # Each word's share of ZIPA's sounds, by lining them up with espeak-ng's sounds for the words.
        expected = espeak.sounds(ws)
        word_of = [k for k, ss in enumerate(expected) for _ in ss]
        flat = [x for ss in expected for x in ss]
        share = [[] for _ in ws]
        cur = 0
        for r, h, _ in align(flat, heard):
            if r is not None:
                cur = word_of[r]
            if h is not None:
                share[cur].append(heard[h])
        out = []
        for k, w in enumerate(ws):
            in_dictionary = w in known or all(p in known for p in re.split(r"(?<=')|-", w) if p)
            if any(c.isdigit() for c in w) or (in_dictionary and not replace_known):
                out.append(w)
                continue
            near = [c for c, _, _ in process.extract(w, lexicon, scorer=Levenshtein.distance,
                                                     score_cutoff=snap_close(w), limit=15) if c != w]
            if not near:
                out.append(w)
                continue
            own = sound_distance(expected[k], share[k])
            best, cost = min(((c, sound_distance(ss, share[k])) for c, ss in zip(near, espeak.sounds(near))),
                             key=lambda cc: cc[1])
            if (in_dictionary and cost < own - ZIPA_MARGIN) or (not in_dictionary and cost <= own + ZIPA_MARGIN):
                out.append(best)
            else:
                out.append(w)
        return " ".join(out)

    return hear, [None]


def omnilingual_dictionary(model_dir, dictionaries):
    """Omnilingual's own outputs, decoded with only the dictionary's words."""
    import numpy as np
    import onnxruntime as ort
    d = glob.glob(f"{model_dir}/sherpa-onnx-omnilingual-*")[0]
    tokens = {}
    for line in open(f"{d}/tokens.txt", encoding="utf-8"):
        t, i = line.rstrip("\n").rsplit(" ", 1)
        tokens[int(i)] = t
    vocab = {t for i, t in tokens.items() if i > 3 and len(t) == 1}
    session = ort.InferenceSession(glob.glob(f"{d}/model*.onnx")[0])
    loaded = {}

    def hear(path, code, language, prompt):
        if code not in loaded:
            loaded.clear()  # One language's words at a time.
            loaded[code] = dictionary_words(f"{dictionaries}/{code}.db", vocab)
        x = load_wav(path)
        x = (x - x.mean()) / np.sqrt(x.var() + 1e-5)
        logits = session.run(None, {"x": x[None]})[0][0]
        logp = logits - logits.max(-1, keepdims=True)
        logp = logp - np.log(np.exp(logp).sum(-1, keepdims=True))
        return dictionary_decode(logp, tokens, loaded[code])

    return hear, [None]


def gemma(path):
    import litert_lm
    engine = litert_lm.Engine(path, backend=litert_lm.Backend.CPU(), audio_backend=litert_lm.Backend.CPU(),
                              max_num_tokens=4096)
    exact = litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)

    def hear(file, code, language, prompt):
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


def pieces(path, vad_model, longest=PIECE_SECONDS):
    """The recording in [path], cut into pieces of at most [longest] seconds. Silero VAD only finds the
    pauses: each cut is in the middle of one, and the pieces keep all of the audio between them, so
    nothing at the start or end of a sentence is lost. Speech with no pause for [longest] seconds is
    cut where it reaches it."""
    import sherpa_onnx
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = vad_model
    config.silero_vad.min_silence_duration = 0.25
    config.silero_vad.min_speech_duration = 0.25
    config.silero_vad.max_speech_duration = longest
    config.sample_rate = RATE
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=FOUR_MINUTES * 2)
    x = load_wav(path)
    window = config.silero_vad.window_size
    speech = []  # (start, end) in samples

    def take():
        while not vad.empty():
            speech.append((vad.front.start, vad.front.start + len(vad.front.samples)))
            vad.pop()

    for i in range(0, len(x), window):
        vad.accept_waveform(x[i:i + window])
        take()
    vad.flush()
    take()
    limit = longest * RATE
    pauses = [(end + nxt) // 2 for (_, end), (nxt, _) in zip(speech, speech[1:])]
    # Cut at the last pause before each piece would pass [longest] seconds.
    out, start, last = [], 0, 0
    for c in pauses + [len(x)]:
        if c - start > limit and last > start:
            out.append(x[start:last])
            start = last
        while c - start > limit:  # No pause in [longest] seconds.
            out.append(x[start:start + limit])
            start += limit
        last = c
    out.append(x[start:])
    return [p for p in out if len(p) > RATE // 10]


def run(args):
    names = {l["code"]: l["englishName"] for l in app_languages()}
    clips = json.load(open(os.path.join(args.clips, "clips.json"), encoding="utf-8"))["clips"]
    if args.only:
        keep = set(args.only.split(","))
        clips = [c for c in clips if c["lang"] in keep]
    if args.recognizer == "parakeet":
        clips = [c for c in clips if c["lang"] in PARAKEET]
    hear, prompts = {
        "omnilingual": lambda: omnilingual(args.model),
        "omnilingual-dictionary": lambda: omnilingual_dictionary(args.model, args.dictionaries),
        "parakeet": lambda: parakeet(args.model),
        "omnilingual-zipa": lambda: omnilingual_zipa(args.model, args.zipa, args.dictionaries),
        "omnilingual-zipa-unknown": lambda: omnilingual_zipa(args.model, args.zipa, args.dictionaries, replace_known=False),
        "gemma": lambda: gemma(args.model),
    }[args.recognizer]()
    if args.tests:
        keep = set(args.tests.split(","))
        clips = [c for c in clips if c["test"] in keep]
    piece_file = os.path.join(os.path.dirname(os.path.abspath(args.out)), "piece.wav")
    os.makedirs(os.path.dirname(piece_file), exist_ok=True)
    results = []
    for c in clips:
        for prompt in prompts:
            start = time.time()
            extra = {}
            try:
                path = os.path.join(args.clips, c["file"])
                if c["test"] == "four-minutes":
                    heard_pieces = []
                    for piece in pieces(path, args.vad):
                        save_wav(piece_file, piece)
                        heard_pieces.append(hear(piece_file, c["lang"], names[c["lang"]], prompt))
                    heard, extra = " ".join(heard_pieces), {"pieces": len(heard_pieces)}
                else:
                    heard = hear(path, c["lang"], names[c["lang"]], prompt)
                error = None
            except Exception as e:  # A clip the recognizer can't take is reported, not fatal.
                heard, error = "", f"{type(e).__name__}: {e}"
            results.append({**c, **extra, "prompt": prompt, "heard": heard, "error": error,
                            "seconds_taken": round(time.time() - start, 2)})
            print(f"{c['file']} {prompt or ''}: {heard[:100]}{' ERROR ' + error if error else ''}", flush=True)
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss // 1024
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump({"name": args.name, "peak_mb": peak, "results": results},
              open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


# ---- snap ----

def snap_close(word):
    """The most letters a snap may change in [word], for "only when it's close": a quarter of them."""
    return max(1, round(len(word) / 4))


def dictionary_by_use(db_path):
    """The dictionary's words (inflected forms included), most common first. Not affixes or phrases."""
    import sqlite3
    use = {}
    for key, freq in sqlite3.connect(db_path).execute("SELECT key, freq FROM entries"):
        w = unicodedata.normalize("NFC", key.lower()).replace("’", "'")
        if w and " " not in w and w[0] != "-" and w[-1] != "-":
            use[w] = max(use.get(w, 0), freq or 0)
    return sorted(use, key=lambda w: -use[w])


def snap_text(text, code, lexicon, known, close):
    """[text] with each word not in the dictionary replaced by the nearest one in spelling. A word is
    known if it's in the dictionary, or each of its parts at an apostrophe or hyphen is (as the app
    looks words up)."""
    from rapidfuzz import process
    from rapidfuzz.distance import Levenshtein
    out = []
    for w in words(text, code):
        parts = [p for p in re.split(r"(?<=')|-", w) if p]
        if w in known or all(p in known for p in parts) or any(c.isdigit() for c in w):
            out.append(w)
            continue
        found = process.extractOne(w, lexicon, scorer=Levenshtein.distance,
                                   score_cutoff=snap_close(w) if close else None)
        out.append(found[0] if found else w)
    return " ".join(out)


def snap(args):
    """Writes a snapped copy of each result whose recognizer is in --names: always, and when close."""
    wanted = set(args.names.split(","))
    os.makedirs(args.out, exist_ok=True)
    for path in args.files:
        run = json.load(open(path, encoding="utf-8"))
        if run["name"] not in wanted:
            continue
        for close, suffix in ((False, "unknown words snapped"), (True, "unknown words snapped when close")):
            results, lexicon, lang = [], None, None
            for x in run["results"]:
                if x["lang"] != lang:
                    lang = x["lang"]
                    lexicon = dictionary_by_use(f"{args.dictionaries}/{lang}.db")
                    known = set(lexicon)
                start = time.time()
                heard = snap_text(x["heard"], lang, lexicon, known, close)
                results.append({**x, "heard": heard, "seconds_taken": round(x["seconds_taken"] + time.time() - start, 2)})
            name = f"{run['name']}, {suffix}"
            out = os.path.join(args.out, re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") + ".json")
            json.dump({**run, "name": name, "results": results}, open(out, "w", encoding="utf-8"),
                      ensure_ascii=False, indent=1)
            print(f"{name}: {len(results)} results", flush=True)


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
    leave_out = set(filter(None, (args.leave_out or "").split(",")))
    runs = [r for r in (json.load(open(p, encoding="utf-8")) for p in args.files)
            if not any(r["name"] == n or r["name"].startswith(n + ",") for n in leave_out)]
    lines = ["# Speech recognition eval", ""]
    cols = [(r, p) for r in runs for p in sorted({x["prompt"] for x in r["results"]}, key=lambda p: p or "")]

    def has(r, p, test):
        return any(x["prompt"] == p and x["test"] == test for x in r["results"])

    def rate(r, prompt, lang, test):
        rows = [x for x in r["results"] if x["prompt"] == prompt and x["lang"] == lang and x["test"] == test]
        if not rows:
            return None
        errs = sum(edits(words(x["text"], lang), words(x["heard"], lang)) for x in rows)
        total = sum(len(words(x["text"], lang)) for x in rows)
        return 100 * errs / max(total, 1)

    langs = sorted({x["lang"] for r in runs for x in r["results"]})
    for test, title in (("accuracy", "Accuracy: word error rate, % (lower is better)"),
                        ("long", "Long speech, about a minute, given whole: word error rate, %"),
                        ("four-minutes", f"Four minutes, cut at the pauses into pieces of up to {PIECE_SECONDS} seconds: "
                                         "word error rate, %"),
                        ("four-minutes-sentences", "The same four minutes, one sentence at a time: word error rate, %")):
        tcols = [(r, p) for r, p in cols if has(r, p, test)]
        if not tcols:
            continue
        lines += [f"## {title}", "", "| Language | " + " | ".join(label(r["name"], p) for r, p in tcols) + " |",
                  "|---|" + "---|" * len(tcols)]
        table = {lang: [rate(r, p, lang, test) for r, p in tcols] for lang in langs}
        table = {lang: vs for lang, vs in table.items() if any(v is not None for v in vs)}
        for lang, vs in table.items():
            lines.append(f"| {lang} | " + " | ".join("" if v is None else f"{v:.0f}" for v in vs) + " |")

        def average(rows):
            cells = []
            for i in range(len(tcols)):
                vs = [row[i] for row in rows if row[i] is not None]
                cells.append(f"**{sum(vs) / len(vs):.0f}**" if vs else "")
            return " | ".join(cells)

        lines.append("| **Average** | " + average(table.values()) + " |")
        shared = [vs for vs in table.values() if None not in vs]
        if len(shared) < len(table):
            lines.append(f"| **Average, the {len(shared)} languages all have** | " + average(shared) + " |")
        lines.append("")

    fcols = [(r, p) for r, p in cols if has(r, p, "faithful")]
    if fcols:
        lines += ["## Faithfulness: a wrong article, kept as said or corrected", "",
                  "| Language | " + " | ".join(label(r["name"], p) + " kept / corrected / other" for r, p in fcols) + " |",
                  "|---|" + "---|" * len(fcols)]
        flangs = sorted({x["lang"] for r in runs for x in r["results"] if x["test"] == "faithful"})
        totals = [[0, 0, 0] for _ in fcols]
        for lang in flangs:
            cells = []
            for i, (r, p) in enumerate(fcols):
                v = [faithful_verdict(x) for x in r["results"] if x["prompt"] == p and x["lang"] == lang and x["test"] == "faithful"]
                k, c, o = v.count("kept"), v.count("corrected"), v.count("other")
                totals[i] = [totals[i][0] + k, totals[i][1] + c, totals[i][2] + o]
                cells.append(f"{k} / {c} / {o}")
            lines.append(f"| {lang} | " + " | ".join(cells) + " |")
        lines.append("| **All** | " + " | ".join(f"**{k} / {c} / {o}**" for k, c, o in totals) + " |")
        lines.append("")

    lines += ["## Time and memory (CI machine, not a phone)", "",
              "| Recognizer | Seconds per short clip | Seconds per minute, whole | Seconds per four minutes, "
              "in pieces | Pieces per four minutes | Peak memory over its run, MB | Errors |",
              "|---|---|---|---|---|---|---|"]

    def mean(vs):
        return f"{sum(vs) / len(vs):.1f}" if vs else ""

    for r, p in cols:
        rows = [x for x in r["results"] if x["prompt"] == p]
        errors = sum(1 for x in rows if x["error"])
        lines.append(f"| {label(r['name'], p)} | "
                     + mean([x["seconds_taken"] for x in rows if x["test"] in ("accuracy", "faithful")]) + " | "
                     + mean([x["seconds_taken"] for x in rows if x["test"] == "long"]) + " | "
                     + mean([x["seconds_taken"] for x in rows if x["test"] == "four-minutes"]) + " | "
                     + mean([x["pieces"] for x in rows if x["test"] == "four-minutes" and "pieces" in x]) + " | "
                     + f"{r['peak_mb']} | {errors} |")
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
    b.add_argument("--recognizer", choices=["omnilingual", "omnilingual-dictionary", "omnilingual-zipa", "omnilingual-zipa-unknown",
                                               "parakeet", "gemma"],
                   required=True)
    b.add_argument("--zipa", help="ZIPA's folder, with model.int8.onnx and tokens.txt (omnilingual-zipa)")
    b.add_argument("--model", required=True)
    b.add_argument("--name", default="Omnilingual")
    b.add_argument("--dictionaries", help="the app's dictionaries, as <code>.db (omnilingual-dictionary)")
    b.add_argument("--clips", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--only", help="comma-separated language codes")
    b.add_argument("--tests", help="comma-separated: accuracy, faithful, long, four-minutes, "
                                   "four-minutes-sentences (default all)")
    b.add_argument("--vad", help="Silero VAD's model, silero_vad.onnx (for four-minutes)")
    d = sub.add_parser("snap")
    d.add_argument("--dictionaries", required=True, help="the app's dictionaries, as <code>.db")
    d.add_argument("--names", required=True, help="comma-separated recognizer names, as in the results")
    d.add_argument("--out", required=True)
    d.add_argument("files", nargs="+")
    c = sub.add_parser("report")
    c.add_argument("--leave-out", help="comma-separated recognizer names whose results (and snapped copies) to leave out")
    c.add_argument("files", nargs="+")
    args = p.parse_args()
    {"prepare": prepare, "run": run, "snap": snap, "report": report}[args.cmd](args)


if __name__ == "__main__":
    main()
