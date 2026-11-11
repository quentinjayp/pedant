"""Calibrates Learning mode's pass marks with real speech.

Learners are real people reading sentences, from Google's FLEURS dataset (CC-BY). Each recording
is scored against its own sentence (should pass) and against wrong versions of it: one word
swapped, one word dropped, and a different sentence (should fail). The scores use the same rules
as app/src/main/java/com/pedant/app/Matching.kt, which this file mirrors; keep the two in step.

Usage: python calibrate_scoring.py <models dir>
The models dir holds the reference vits-piper-* voices, the Omnilingual ASR model, ZIPA, the word
dictionaries (dict/) and FLEURS (fleurs/<code>/); see the CI job.
"""
from array import array
import glob
import os
import re
import sqlite3
import sys
import unicodedata

import sherpa_onnx

MODELS = sys.argv[1]

# Per language: the app's voice (the reference), FLEURS's code for it, and words to swap in.
LANGS = {
    "fr": {"ref": "fr_FR-siwis-medium", "fleurs": "fr_fr", "swap": ["chien", "rouge", "jamais", "maison"]},
    "es": {"ref": "es_ES-davefx-medium", "fleurs": "es_419", "swap": ["perro", "rojo", "nunca", "casa"]},
    "de": {"ref": "de_DE-thorsten-medium", "fleurs": "de_de", "swap": ["Hund", "rot", "niemals", "Haus"]},
    "en": {"ref": "en_US-lessac-medium", "fleurs": "en_us", "swap": ["dog", "red", "never", "house"]},
}
# Sentences per language, and their length in words (the app's phrases are short).
PER_LANGUAGE = 25
MIN_WORDS, MAX_WORDS = 4, 10

# ---- Scoring: mirrors Matching.kt -------------------------------------------------------------

CLOSE_WORD = 0.66
CLOSE_SOUNDS = [
    {"i", "ɪ", "y", "ʏ", "j"},
    {"e", "ɛ", "ə", "ɘ", "œ", "ø", "ɜ", "æ"},
    {"a", "ɑ", "æ", "ɐ", "ʌ"},
    {"o", "ɔ", "ɒ", "ʌ", "ɤ"},
    {"u", "ʊ", "ɯ", "w"},
    {"r", "ʁ", "ɹ", "ʀ", "ɾ", "ɻ", "χ"},
    {"g", "ɡ"},
    {"ʃ", "ʂ", "ɕ"},
    {"ʒ", "ʐ", "ʑ"},
    {"n", "ŋ", "ɲ"},
    {"l", "ɫ", "ʎ"},
]
VOICING = {(a, b) for a, b in [("p", "b"), ("t", "d"), ("k", "g"), ("k", "ɡ"), ("s", "z"), ("f", "v"), ("ʃ", "ʒ"), ("θ", "ð")]}
VOICING |= {(b, a) for a, b in VOICING}


def is_mark(s):
    return bool(s) and all(unicodedata.category(c) == "Mn" or c in "ˈˌːˑ˞ʰʲʷ" for c in s)


# A carried consonant (French liaison, like the z of "les enfants") is written "‿z". It's expected,
# but leaving it out costs half a sound, since some liaisons are optional.
CARRIED = "‿"


def base(s):
    return s[1:] if s.startswith(CARRIED) else s


def sound_indel(s):
    if s.startswith(CARRIED):
        return 0.5
    return 0.3 if is_mark(s) else 1.0


def sound_cost(a, b):
    a, b = base(a), base(b)
    if a == b:
        return 0.0
    if is_mark(a) or is_mark(b):
        return 0.3
    if any(a in g and b in g for g in CLOSE_SOUNDS):
        return 0.35
    if (a, b) in VOICING:
        return 0.5
    return 1.0


def align(ref, hyp, sub, indel):
    n, m = len(ref), len(hyp)
    d = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        d[i][0] = d[i - 1][0] + indel(ref[i - 1])
    for j in range(1, m + 1):
        d[0][j] = d[0][j - 1] + indel(hyp[j - 1])
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j - 1] + sub(ref[i - 1], hyp[j - 1]),
                          d[i - 1][j] + indel(ref[i - 1]),
                          d[i][j - 1] + indel(hyp[j - 1]))
    steps, i, j = [], n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0:
            c = sub(ref[i - 1], hyp[j - 1])
            if d[i][j] == d[i - 1][j - 1] + c:
                steps.append((i - 1, j - 1, c)); i -= 1; j -= 1; continue
        if i > 0 and d[i][j] == d[i - 1][j] + indel(ref[i - 1]):
            steps.append((i - 1, None, indel(ref[i - 1]))); i -= 1
        else:
            steps.append((None, j - 1, indel(hyp[j - 1]))); j -= 1
    return steps[::-1]


def normalize(text):
    t = unicodedata.normalize("NFD", text.lower())
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    t = "".join(" " if unicodedata.category(c)[0] in "PS" else c for c in t)
    return " ".join(t.split())


def words(text):
    out = []
    for w in text.split():
        while w and not w[0].isalnum():
            w = w[1:]
        while w and not w[-1].isalnum():
            w = w[:-1]
        if w:
            out.append(w)
    return out


def word_cost(a, b):
    if a == b:
        return 0.0
    dist = sum(c for _, _, c in align(list(a), list(b), lambda x, y: 0.0 if x == y else 1.0, lambda _: 1.0))
    sim = 1.0 - dist / max(len(a), len(b))
    return 0.0 if sim >= CLOSE_WORD else 1.0 - sim


def score(steps, total):
    return 0.0 if total <= 0 else max(0.0, min(1.0, 1.0 - sum(c for _, _, c in steps) / total))


def segment(phrase_sounds, isolated):
    if len(isolated) <= 1:
        return [(isolated[0][0] if isolated else "", phrase_sounds)]
    guide = [s for _, ss in isolated for s in ss]
    guide_word = [w for w, (_, ss) in enumerate(isolated) for _ in ss]
    word_of = [0] * len(phrase_sounds)
    cur = 0
    for r, h, _ in align(phrase_sounds, guide, sound_cost, sound_indel):
        if h is not None:
            cur = guide_word[h]
        if r is not None:
            word_of[r] = cur
    return [(w, [s for i, s in enumerate(phrase_sounds) if word_of[i] == k] or iso)
            for k, (w, iso) in enumerate(isolated)]


def ratios_from_steps(ref_words, steps):
    """Each reference word's sound error ratio, from an alignment of all its sounds."""
    ref_word_of = [w for w, (_, ss) in enumerate(ref_words) for _ in ss]
    cost = [0.0] * len(ref_words)
    last = 0
    for r, h, c in steps:
        if r is not None:
            last = ref_word_of[r]
        cost[last] += c
    return [cost[w] / max(1, len(ss)) for w, (_, ss) in enumerate(ref_words)]


def word_ratios(ref_words, heard):
    ref = [s for _, ss in ref_words for s in ss]
    return ratios_from_steps(ref_words, align(ref, heard, sound_cost, sound_indel))


def check(target, transcript, ref_words, heard_sounds):
    """Returns (word score, sound score, per-word: (heard by Omnilingual, sound error ratio))."""
    tw = [normalize(w) for w in words(target)]
    hw = [normalize(w) for w in words(transcript)]
    word_steps = align(tw, hw, word_cost, lambda _: 1.0)
    word_score = score(word_steps, len(tw))
    word_ok = [False] * len(tw)
    for r, h, c in word_steps:
        if r is not None and h is not None and c == 0:
            word_ok[r] = True
    ref = [s for _, ss in ref_words for s in ss]
    sound_steps = align(ref, heard_sounds, sound_cost, sound_indel)
    sound_score = score(sound_steps, sum(sound_indel(s) for s in ref))
    return word_score, sound_score, list(zip(word_ok, ratios_from_steps(ref_words, sound_steps)))


# ---- Expected sounds from the dictionary (option B) --------------------------------------------

DICTS = os.path.join(MODELS, "dict")
VOWELS = set("aeiouyøœɛɔəɑɐɒæɪʊʏɨʉɯɤɜɞʌɘɵ")
IPA_DROP = set("/[].ˈˌ‿͜͡ -")


def ipa_symbols(ipa):
    """Dictionary IPA as ZIPA-style symbols: one per character, combining marks on their own,
    without syllable dots, stress marks, link marks, tie bars or optional (bracketed) sounds."""
    ipa = re.sub(r"\([^)]*\)", "", unicodedata.normalize("NFD", ipa))
    return [c for c in ipa if c not in IPA_DROP and not c.isspace()]


def dict_key(word):
    return unicodedata.normalize("NFC", word).lower().replace("’", "'")


def dict_ipa(db, word):
    """The dictionary's IPA for [word] as symbols, as the app finds it (elided words by their parts)."""
    def whole(w):
        row = db.execute("SELECT ipa FROM entries WHERE key = ? AND ipa IS NOT NULL LIMIT 1", (dict_key(w),)).fetchone()
        return ipa_symbols(row[0]) if row else None
    found = whole(word)
    if found:
        return found
    cut = next((i for i, c in enumerate(word) if c in "'’"), -1)
    if 0 < cut < len(word) - 1:
        part, main = whole(word[:cut + 1]), whole(word[cut + 1:])
        if part and main:
            return part + main
    return None


def dict_candidates(db, word):
    """Every pronunciation the dictionary lists for [word], as symbols (elided words by their parts)."""
    def whole(w):
        rows = db.execute("SELECT ipas FROM entries WHERE key = ? AND ipas IS NOT NULL", (dict_key(w),)).fetchall()
        out = []
        for (ipas,) in rows:
            for v in ipas.split("\t"):
                syms = ipa_symbols(v)
                if syms and syms not in out:
                    out.append(syms)
        return out
    found = whole(word)
    if found:
        return found
    cut = next((i for i, c in enumerate(word) if c in "'’"), -1)
    if 0 < cut < len(word) - 1:
        parts, mains = whole(word[:cut + 1]), whole(word[cut + 1:])
        return [p + m for p in parts for m in mains]
    return []


# Piper's sounds for a word count as agreeing with the dictionary when they're this close to one of
# its pronunciations (cost per expected sound).
AGREE = 0.15


def pron_ratio(said, expected):
    return sum(c for _, _, c in align(expected, said, sound_cost, sound_indel)) / max(1, len(expected))


def is_consonant(s):
    return bool(s) and not is_mark(s) and s not in VOWELS and s != "▁"


def dict_reference(db, phrase_sounds, current, check=False):
    """Option B: each word's sounds from the dictionary (Piper's where it has none), plus the
    consonants Piper carries over from one word to a next word that starts with a vowel.

    Option B2 ([check]): Piper's sounds for a word are kept when they agree with one of the
    dictionary's pronunciations, and replaced by the closest one when they don't."""
    refs = []
    for w, piper in current:
        piper = [x for x in piper if x != "▁"]
        if check:
            cands = dict_candidates(db, w) if db else []
            best = min(cands, key=lambda c: pron_ratio(piper, c)) if cands else None
            if best is None or pron_ratio(piper, best) <= AGREE:
                refs.append((w, piper, False))
            else:
                refs.append((w, best, True))
            continue
        ipa = dict_ipa(db, w) if db else None
        refs.append((w, ipa if ipa else list(piper), ipa is not None))
    ref = [s for _, ss, _ in refs for s in ss]
    word_of = [i for i, (_, ss, _) in enumerate(refs) for _ in ss]
    carried = {}
    last_ref = None
    for r, h, _ in align(ref, [s for s in phrase_sounds if s != "▁"], sound_cost, sound_indel):
        if r is not None:
            last_ref = r
            continue
        # A sound Piper said between the end of one word and the start of the next.
        if last_ref is None or last_ref + 1 >= len(ref):
            continue
        w = word_of[last_ref]
        if word_of[last_ref + 1] != w + 1 or last_ref != max(i for i, x in enumerate(word_of) if x == w):
            continue
        sound = [s for s in phrase_sounds if s != "▁"][h]
        nxt = refs[w + 1][1]
        if is_consonant(sound) and nxt and base(nxt[0]) in VOWELS:
            carried[w] = CARRIED + sound
    out = [(w, ss + ([carried[i]] if i in carried else [])) for i, (w, ss, _) in enumerate(refs)]
    coverage = sum(1 for _, _, from_dict in refs if from_dict) / max(1, len(refs))
    return out, coverage


# ---- Models -----------------------------------------------------------------------------------

def tts(voice):
    d = f"{MODELS}/vits-piper-{voice}"
    onnx = glob.glob(f"{d}/*.onnx")[0]
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=onnx, tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
        num_threads=4))
    return sherpa_onnx.OfflineTts(cfg)


asr_dir = glob.glob(f"{MODELS}/sherpa-onnx-omnilingual-*")[0]
asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
    model=f"{asr_dir}/model.int8.onnx", tokens=f"{asr_dir}/tokens.txt", num_threads=4)
zipa = sherpa_onnx.OfflineRecognizer.from_zipformer_ctc(
    model=f"{MODELS}/zipa/model.int8.onnx", tokens=f"{MODELS}/zipa/tokens.txt", num_threads=4)


def recognize(rec, audio):
    s = rec.create_stream()
    s.accept_waveform(audio.sample_rate, audio.samples)
    rec.decode_stream(s)
    return s.result


class Padded:
    """Audio with silence before and after, padded to at least a second, as the app does before ZIPA."""
    def __init__(self, audio):
        self.sample_rate = audio.sample_rate
        margin = audio.sample_rate // 4
        samples = [0.0] * margin + list(audio.samples) + [0.0] * margin
        self.samples = samples + [0.0] * max(0, audio.sample_rate - len(samples))


def sounds(audio):
    return [t.strip() for t in recognize(zipa, Padded(audio)).tokens if t.strip()]


def variants(phrase, swap):
    ws = phrase.split()
    k = len(ws) // 2
    yield "swap one word", " ".join(ws[:k] + [swap] + ws[k + 1:])
    yield "drop one word", " ".join(ws[:k] + ws[k + 1:])


class Clip:
    def __init__(self, samples, sample_rate):
        self.samples, self.sample_rate = samples, sample_rate


def read_wav(path):
    """A WAV file's first channel as floats: 16-bit PCM or 32-bit float (as FLEURS uses)."""
    data = open(path, "rb").read()
    pos, fmt, samples = 12, None, None
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], int.from_bytes(data[pos + 4:pos + 8], "little")
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt = (int.from_bytes(body[0:2], "little"), int.from_bytes(body[2:4], "little"),
                   int.from_bytes(body[4:8], "little"), int.from_bytes(body[14:16], "little"))
        elif cid == b"data":
            samples = body
        pos += 8 + size + (size & 1)
    tag, channels, rate, bits = fmt
    if tag == 3 and bits == 32:
        values = list(array("f", samples[: len(samples) // 4 * 4]))
    elif tag == 1 and bits == 16:
        values = [x / 32768.0 for x in array("h", samples[: len(samples) // 2 * 2])]
    else:
        raise ValueError(f"Unsupported WAV format {tag}/{bits} in {path}")
    return Clip(values[::channels], rate)


def fleurs_sentences(code):
    """Short FLEURS sentences without numbers, with their recordings."""
    d = os.path.join(MODELS, "fleurs", code)
    wavs = {os.path.basename(p): p for p in glob.glob(f"{d}/**/*.wav", recursive=True)}
    out, seen = [], set()
    for line in open(os.path.join(d, "dev.tsv"), encoding="utf-8"):
        cols = line.rstrip("\n").split("\t")
        if len(cols) < 3:
            continue
        file, text = cols[1], cols[2]
        if file not in wavs or text in seen or any(c.isdigit() for c in text):
            continue
        if not MIN_WORDS <= len(words(text)) <= MAX_WORDS:
            continue
        seen.add(text)
        out.append((text, wavs[file]))
        if len(out) >= PER_LANGUAGE:
            break
    return out


rows = []
coverage = {}
replacements = {}
for lang, cfg in LANGS.items():
    db_path = os.path.join(DICTS, f"{lang}.db")
    db = sqlite3.connect(db_path) if os.path.exists(db_path) else None
    ref_tts = tts(cfg["ref"])
    references_cache = {}

    def references(phrase):
        """The expected sounds for [phrase], by each method."""
        if phrase not in references_cache:
            audio = ref_tts.generate(phrase, sid=0, speed=1.0)
            isolated = [(w, sounds(ref_tts.generate(w, sid=0, speed=1.0))) for w in words(phrase)]
            phrase_sounds = sounds(audio)
            ref_words = segment(phrase_sounds, isolated)
            dict_words, cov = dict_reference(db, phrase_sounds, ref_words)
            checked_words, replaced = dict_reference(db, phrase_sounds, ref_words, check=True)
            coverage.setdefault(lang, []).append(cov)
            replacements.setdefault(lang, []).append(replaced)
            print(f"{lang} {phrase}")
            print("   current:    " + " | ".join(f"{w} /{''.join(ss)}/" for w, ss in ref_words))
            print("   dictionary: " + " | ".join(f"{w} /{''.join(ss)}/" for w, ss in dict_words))
            print("   checked:    " + " | ".join(f"{w} /{''.join(ss)}/" for w, ss in checked_words), flush=True)
            references_cache[phrase] = [("current", ref_words), ("dictionary", dict_words), ("checked", checked_words)]
        return references_cache[phrase]

    sentences = fleurs_sentences(cfg["fleurs"])
    print(f"== {lang}: {len(sentences)} FLEURS sentences", flush=True)
    for i, (text, path) in enumerate(sentences):
        clip = read_wav(path)
        transcript = recognize(asr, clip).text
        heard_sounds = sounds(clip)
        alike_sounds = sounds(ref_tts.generate(transcript, sid=0, speed=1.0)) if transcript.strip() else None
        other = sentences[(i + 1) % len(sentences)][0]
        # The recording is always the real sentence; the wrong cases score it against a wrong target.
        cases = [("correct", text)] + list(variants(text, cfg["swap"][i % len(cfg["swap"])])) + [("different sentence", other)]
        for kind, target in cases:
            for method, refs in references(target):
                ws, ss, per_word = check(target, transcript, refs, heard_sounds)
                # Sound-alike check: the reference voice says what Omnilingual heard.
                alike = word_ratios(refs, alike_sounds) if alike_sounds else [1.0] * len(per_word)
                # Per word: (Omnilingual heard it, ZIPA's sound error ratio, the sound-alike ratio).
                per_word = [(heard, ratio, alike_ratio) for (heard, ratio), alike_ratio in zip(per_word, alike)]
                rows.append((lang, kind, os.path.basename(path), 0, ws, ss, per_word, method))
        print(f"{lang} heard: {transcript}", flush=True)

print("\n== Words with dictionary IPA, by language ==")
for lang, covs in coverage.items():
    print(f"{lang} {sum(covs) / len(covs):.0%}")
print("\n== Words where Piper disagreed with the dictionary and were replaced (checked) ==")
for lang, reps in replacements.items():
    print(f"{lang} {sum(reps) / len(reps):.0%}")

# The app's rule: a word is right if Omnilingual heard it, OR ZIPA's sounds for it are close
# enough, OR what Omnilingual heard sounds like it (the sound-alike check). Each part alone and in
# pairs shows how much each recognizer adds.
LIMIT = 0.5
KINDS = ["correct", "swap one word", "drop one word", "different sentence"]
RULES = {
    "ZIPA alone": lambda heard, ratio, alike: ratio <= LIMIT,
    "Omnilingual alone": lambda heard, ratio, alike: heard,
    "Omnilingual + sound-alike": lambda heard, ratio, alike: heard or alike <= LIMIT,
    "ZIPA + Omnilingual": lambda heard, ratio, alike: heard or ratio <= LIMIT,
    "All three (the app)": lambda heard, ratio, alike: heard or ratio <= LIMIT or alike <= LIMIT,
}


def bad_words(r, rule=RULES["All three (the app)"]):
    return [i for i, w in enumerate(r[6]) if not rule(*w)]


for method in ["current", "dictionary", "checked"]:
    group_rows = [r for r in rows if r[7] == method]
    print(f"\n== Expected sounds: {method} ==")
    for kind in KINDS:
        for metric, idx in [("words", 4), ("sounds", 5)]:
            vals = [r[idx] for r in group_rows if r[1] == kind]
            print(f"{kind:18} {metric:6} min {min(vals):.2f} mean {sum(vals) / len(vals):.2f} max {max(vals):.2f}")
    print(f"{'rule':28}" + "".join(f"{k:>20}" for k in KINDS))
    for name, rule in RULES.items():
        cells = []
        for kind in KINDS:
            group = [r for r in group_rows if r[1] == kind]
            cells.append(f"{sum(not bad_words(r, rule) for r in group) / max(1, len(group)):>20.0%}")
        print(f"{name:28}" + "".join(cells))
    print("By language, correct attempts passing the app's rule: " + ", ".join(
        f"{lang} {sum(not bad_words(r) for r in group_rows if r[0] == lang and r[1] == 'correct') / max(1, sum(1 for r in group_rows if r[0] == lang and r[1] == 'correct')):.0%}"
        for lang in LANGS))
    print("Correct attempts that fail the app's rule:")
    for r in group_rows:
        if r[1] == "correct" and bad_words(r):
            print("  ", r[0], r[2], r[3], "bad word indexes", bad_words(r))
