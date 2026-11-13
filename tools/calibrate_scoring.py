"""Calibrates the pass marks with real speech.

Learners are real people reading sentences, from Google's FLEURS dataset (CC-BY). Each recording
is scored against its own sentence (should pass) and against wrong versions of it: one word
swapped, one word added that the speaker skipped, and a different sentence (should fail). The scores use the same rules
as app/src/main/java/com/pedant/app/Matching.kt, which this file mirrors; keep the two in step.

Usage: python calibrate_scoring.py <models dir>
Expected sounds, three ways: "current", ZIPA's hearing of the app's voice saying the phrase;
"espeak", the phonemes the voice is given (espeak-ng's, which the app shows); and "either", the app's
rule: a word counts as right when it's close to either. Earlier experiments (Wiktionary's IPA, Piper
checked against it, liaison marking, another word split) lost to these and were removed.

The models dir holds the reference vits-piper-* voices, the Omnilingual ASR model, ZIPA and FLEURS
(fleurs/<code>/); see the CI job. espeak-ng must be installed.
"""
from array import array
import glob
import os
import re
import json
import subprocess
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
    # Mandarin's words are its characters, and FLEURS has few sentences of 12 or fewer.
    "zh": {"ref": "zh_CN-huayan-medium", "fleurs": "cmn_hans_cn", "swap": ["狗", "红", "不", "家"], "max_words": 20},
}
# Reported apart: Mandarin, written without spaces, is graded a character at a time.
GROUPS = {"fr, es, de, en": ["fr", "es", "de", "en"], "zh": ["zh"]}
# Sentences per language, and their length in words (the app's phrases are short).
PER_LANGUAGE = 25
MIN_WORDS, MAX_WORDS = 4, 12

# ---- Scoring: mirrors Matching.kt -------------------------------------------------------------

CLOSE_WORD = 0.66
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


def is_mark(s):
    return bool(s) and all(unicodedata.category(c) == "Mn" or c in "ˈˌːˑ˞ʰʲʷ" for c in s)


def sound_indel(s):
    return 0.3 if is_mark(s) else 1.0


def one_g(s):
    return "ɡ" if s == "g" else s


def sound_cost(a, b):
    a, b = one_g(a), one_g(b)
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


def is_han(c):
    """Whether [c] is a Chinese character (the main blocks; the app asks for its Unicode script)."""
    return "\u3400" <= c <= "\u4dbf" or "\u4e00" <= c <= "\u9fff" or "\uf900" <= c <= "\ufaff" or "\U00020000" <= c <= "\U0003ffff"


def words(text):
    """The words of [text], as the app's wordsOf splits them: runs of letters, marks, digits,
    apostrophes and hyphens with at least one letter or digit, and each Chinese character on its own."""
    out, run = [], ""

    def end_run():
        nonlocal run
        if any(c.isalnum() for c in run):
            out.append(run)
        run = ""

    for c in text:
        if is_han(c):
            end_run()
            out.append(c)
        elif unicodedata.category(c)[0] in "LMN" or c in "'’-":
            run += c
        else:
            end_run()
    end_run()
    return out


def word_cost(a, b):
    if a == b:
        return 0.0
    dist = sum(c for _, _, c in align(list(a), list(b), lambda x, y: 0.0 if x == y else 1.0, lambda _: 1.0))
    sim = 1.0 - dist / max(len(a), len(b))
    return 0.0 if sim >= CLOSE_WORD else 1.0 - sim


def score(steps, total):
    return 0.0 if total <= 0 else max(0.0, min(1.0, 1.0 - sum(c for _, _, c in steps) / total))


def espeak_voice(voice):
    """The espeak-ng voice a Piper voice reads its text with (from its .onnx.json)."""
    return json.load(open(glob.glob(f"{MODELS}/vits-piper-{voice}/*.onnx.json")[0], encoding="utf-8"))["espeak"]["voice"]


def espeak_ipa(text, voice):
    """espeak-ng's IPA for [text], a string per word, as Piper is given it (without stress marks
    and the like, which ipa_symbols drops)."""
    out = subprocess.run(["espeak-ng", "-q", "--ipa", "-v", voice], input=text, capture_output=True, text=True).stdout
    out = re.sub(r"\([a-z-]+\)", "", out)  # language switches, like (en)
    return out.split()


ESPEAK_SPLIT = {"matched": 0, "word by word": 0}


def espeak_words(phrase, voice):
    """The expected sounds per word from espeak-ng. The phrase's IPA, said as a whole (with its
    liaisons), when it has a word for each of the phrase's words; otherwise each word on its own."""
    ws = words(phrase)
    whole = espeak_ipa(phrase, voice)
    if len(whole) == len(ws):
        ESPEAK_SPLIT["matched"] += 1
        return [(w, ipa_symbols(i)) for w, i in zip(ws, whole)]
    ESPEAK_SPLIT["word by word"] += 1
    return [(w, ipa_symbols("".join(espeak_ipa(w, voice)))) for w in ws]


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


IPA_DROP = set("/[].ˈˌ‿͜͡ -")


def ipa_symbols(ipa):
    """espeak-ng's IPA as ZIPA-style symbols: one per character, combining marks on their own,
    without syllable dots, stress marks, link marks, tie bars or tone numbers."""
    ipa = re.sub(r"\([^)]*\)", "", unicodedata.normalize("NFD", ipa))
    return [c for c in ipa if c not in IPA_DROP and not c.isspace() and not c.isdigit()]


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
    # The recording can't lose a word, so for "skip" the target gains one the speaker never said.
    han = [i for i, c in enumerate(phrase) if is_han(c)]
    if han:
        # Written without spaces: the middle character is the word swapped or added.
        k = han[len(han) // 2]
        yield "swap one word", phrase[:k] + swap + phrase[k + 1:]
        yield "skip one word", phrase[:k] + swap + phrase[k:]
        return
    ws = phrase.split()
    k = len(ws) // 2
    yield "swap one word", " ".join(ws[:k] + [swap] + ws[k + 1:])
    yield "skip one word", " ".join(ws[:k] + [swap] + ws[k:])


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


def fleurs_sentences(code, max_words):
    """Short FLEURS sentences without numbers (or, in Chinese, other writing mixed in), with their recordings."""
    d = os.path.join(MODELS, "fleurs", code)
    wavs = {os.path.basename(p): p for p in glob.glob(f"{d}/**/*.wav", recursive=True)}
    out, seen = [], set()
    lines = [l for split in ("dev", "test") for l in open(os.path.join(d, f"{split}.tsv"), encoding="utf-8")]
    for line in lines:
        cols = line.rstrip("\n").split("\t")
        if len(cols) < 3:
            continue
        file, text = cols[1], cols[2]
        if file not in wavs or text in seen or any(c.isdigit() for c in text):
            continue
        if any(is_han(c) for c in text) and any(c.isascii() and c.isalpha() for c in text):
            continue
        if not MIN_WORDS <= len(words(text)) <= max_words:
            continue
        seen.add(text)
        out.append((text, wavs[file]))
        if len(out) >= PER_LANGUAGE:
            break
    return out


rows = []
for lang, cfg in LANGS.items():
    ref_tts = tts(cfg["ref"])
    references_cache = {}
    alone_cache = {}

    def alone(word):
        """The sounds of [word] said on its own, remembered as the app does."""
        if word not in alone_cache:
            alone_cache[word] = sounds(ref_tts.generate(word, sid=0, speed=1.0))
        return alone_cache[word]

    def references(phrase):
        """The expected sounds for [phrase], by each method."""
        if phrase not in references_cache:
            audio = ref_tts.generate(phrase, sid=0, speed=1.0)
            isolated = [(w, alone(w)) for w in words(phrase)]
            phrase_sounds = sounds(audio)
            ref_words = segment(phrase_sounds, isolated)
            espeak_ref = espeak_words(phrase, espeak_voice(cfg["ref"]))
            print(f"{lang} {phrase}")
            print("   current: " + " | ".join(f"{w} /{''.join(ss)}/" for w, ss in ref_words))
            print("   espeak:  " + " | ".join(f"{w} /{''.join(ss)}/" for w, ss in espeak_ref), flush=True)
            references_cache[phrase] = [("current", ref_words), ("espeak", espeak_ref)]
        return references_cache[phrase]

    sentences = fleurs_sentences(cfg["fleurs"], cfg.get("max_words", MAX_WORDS))
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
            scored = {}
            for method, refs in references(target):
                ws, ss, per_word = check(target, transcript, refs, heard_sounds)
                # Sound-alike check: the reference voice says what Omnilingual heard.
                alike = word_ratios(refs, alike_sounds) if alike_sounds else [1.0] * len(per_word)
                # Per word: (Omnilingual heard it, ZIPA's sound error ratio, the sound-alike ratio).
                per_word = [(heard, ratio, alike_ratio) for (heard, ratio), alike_ratio in zip(per_word, alike)]
                rows.append((lang, kind, os.path.basename(path), ws, ss, per_word, method))
                scored[method] = (ws, ss, per_word)
            # "either": espeak-ng's sounds are shown, and a word counts as right when what was said
            # is close to them or to ZIPA's hearing of Piper (current), whichever is closer.
            (ws, ss_c, cur), (_, ss_e, esp) = scored["current"], scored["espeak"]
            either = [(h1 or h2, min(r1, r2), min(a1, a2)) for (h1, r1, a1), (h2, r2, a2) in zip(cur, esp)]
            rows.append((lang, kind, os.path.basename(path), ws, max(ss_c, ss_e), either, "either"))
        print(f"{lang} heard: {transcript}", flush=True)

# The app's rule: a word is right if Omnilingual heard it, OR ZIPA's sounds for it are close
# enough, OR what Omnilingual heard sounds like it (the sound-alike check). Each part alone and in
# pairs shows how much each recognizer adds.
LIMIT = 0.5
KINDS = ["correct", "swap one word", "skip one word", "different sentence"]
RULES = {
    "ZIPA alone": lambda heard, ratio, alike: ratio <= LIMIT,
    "Omnilingual alone": lambda heard, ratio, alike: heard,
    "Omnilingual + sound-alike": lambda heard, ratio, alike: heard or alike <= LIMIT,
    "ZIPA + Omnilingual": lambda heard, ratio, alike: heard or ratio <= LIMIT,
    "All three (the app)": lambda heard, ratio, alike: heard or ratio <= LIMIT or alike <= LIMIT,
}


def bad_words(r, rule=RULES["All three (the app)"]):
    return [i for i, w in enumerate(r[5]) if not rule(*w)]


print(f"\n== espeak-ng's words lined up with the phrase's: {ESPEAK_SPLIT}")
for method in ["current", "espeak", "either"]:
    for group, codes in GROUPS.items():
        group_rows = [r for r in rows if r[6] == method and r[0] in codes]
        print(f"\n== Expected sounds: {method}, {group} ==")
        for kind in KINDS:
            for metric, idx in [("words", 3), ("sounds", 4)]:
                vals = [r[idx] for r in group_rows if r[1] == kind]
                print(f"{kind:18} {metric:6} min {min(vals):.2f} mean {sum(vals) / len(vals):.2f} max {max(vals):.2f}")
        print(f"{'rule':28}" + "".join(f"{k:>20}" for k in KINDS))
        by_kind = {kind: [r for r in group_rows if r[1] == kind] for kind in KINDS}
        for name, rule in RULES.items():
            print(f"{name:28}" + "".join(
                f"{sum(not bad_words(r, rule) for r in by_kind[kind]) / max(1, len(by_kind[kind])):>20.0%}" for kind in KINDS))
        # Before its characters were graded one by one, Mandarin passed on the whole phrase's scores.
        print(f"{'Whole phrase (0.7 and 0.6)':28}" + "".join(
            f"{sum(r[3] >= 0.7 and r[4] >= 0.6 for r in by_kind[kind]) / max(1, len(by_kind[kind])):>20.0%}" for kind in KINDS))
        print("By language, correct attempts passing the app's rule: " + ", ".join(
            f"{lang} {sum(not bad_words(r) for r in by_kind['correct'] if r[0] == lang) / max(1, sum(1 for r in by_kind['correct'] if r[0] == lang)):.0%}"
            for lang in codes))
        print("Correct attempts that fail the app's rule:")
        for r in by_kind["correct"]:
            if bad_words(r):
                print("  ", r[0], r[2], "bad word indexes", bad_words(r))
