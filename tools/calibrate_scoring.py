"""Calibrates Learning mode's pass marks.

Other Piper voices stand in for learners: each "learner" voice says the target phrase (should
pass) and some wrong versions of it (should fail). The scores use the same rules as
app/src/main/java/com/pedant/app/Matching.kt, which this file mirrors; keep the two in step.

Usage: python calibrate_scoring.py <models dir>
The models dir holds vits-piper-* voices, the Omnilingual ASR model and ZIPA (see the CI job).
"""
import glob
import os
import sys
import unicodedata

import sherpa_onnx

MODELS = sys.argv[1]

LANGS = {
    "fr": {
        "ref": "fr_FR-siwis-medium",
        "learners": [("fr_FR-tom-medium", 0), ("fr_FR-upmc-medium", 0), ("fr_FR-upmc-medium", 1), ("fr_FR-gilles-low", 0)],
        "phrases": [
            "Je vais bien, et toi ?",
            "Ça va, et toi ?",
            "Je voudrais un café, s'il vous plaît.",
            "Où est la gare ?",
            "J'aime beaucoup cette ville.",
            "Nous allons au marché demain matin.",
        ],
        "swap": ["chien", "rouge", "jamais", "maison"],
    },
    "es": {
        "ref": "es_ES-davefx-medium",
        "learners": [("es_ES-sharvard-medium", 0), ("es_ES-sharvard-medium", 1), ("es_MX-ald-medium", 0), ("es_MX-claude-high", 0)],
        "phrases": [
            "Estoy bien, ¿y tú?",
            "Quisiera un café, por favor.",
            "¿Dónde está la estación?",
            "Me gusta mucho esta ciudad.",
            "Mañana vamos al mercado.",
        ],
        "swap": ["perro", "rojo", "nunca", "casa"],
    },
    "de": {
        "ref": "de_DE-thorsten-medium",
        "learners": [("de_DE-thorsten_emotional-medium", 0), ("de_DE-kerstin-low", 0), ("de_DE-eva_k-x_low", 0)],
        "phrases": [
            "Mir geht es gut, und dir?",
            "Ich hätte gern einen Kaffee, bitte.",
            "Wo ist der Bahnhof?",
            "Ich mag diese Stadt sehr.",
            "Morgen gehen wir auf den Markt.",
        ],
        "swap": ["Hund", "rot", "niemals", "Haus"],
    },
    "en": {
        "ref": "en_US-lessac-medium",
        "learners": [("en_US-amy-medium", 0), ("en_US-ryan-medium", 0), ("en_GB-alan-medium", 0)],
        "phrases": [
            "I'm doing well, and you?",
            "I would like a coffee, please.",
            "Where is the train station?",
            "I really like this city.",
            "Tomorrow we are going to the market.",
        ],
        "swap": ["dog", "red", "never", "house"],
    },
}

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


def sound_indel(s):
    return 0.3 if is_mark(s) else 1.0


def sound_cost(a, b):
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


rows = []
for lang, cfg in LANGS.items():
    ref_tts = tts(cfg["ref"])
    learners = [(tts(v), v, sid) for v, sid in cfg["learners"]]
    for p_i, phrase in enumerate(cfg["phrases"]):
        audio = ref_tts.generate(phrase, sid=0, speed=1.0)
        isolated = [(w, sounds(ref_tts.generate(w, sid=0, speed=1.0))) for w in words(phrase)]
        ref_words = segment(sounds(audio), isolated)
        others = cfg["phrases"][(p_i + 1) % len(cfg["phrases"])]
        cases = [("correct", phrase)] + list(variants(phrase, cfg["swap"][p_i % len(cfg["swap"])])) + [("different sentence", others)]
        for engine, voice, sid in learners:
            for kind, text in cases:
                a = engine.generate(text, sid=sid, speed=1.0)
                transcript = recognize(asr, a).text
                ws, ss, per_word = check(phrase, transcript, ref_words, sounds(a))
                # Sound-alike check: the reference voice says what Omnilingual heard.
                alike = word_ratios(ref_words, sounds(ref_tts.generate(transcript, sid=0, speed=1.0))) \
                    if transcript.strip() else [1.0] * len(per_word)
                per_word = [(heard, min(ratio, alike_ratio)) for (heard, ratio), alike_ratio in zip(per_word, alike)]
                rows.append((lang, kind, voice, sid, ws, ss, per_word))
                print(f"{lang} {kind:18} {voice}#{sid:<2} words {ws:.2f} sounds {ss:.2f} | {transcript}", flush=True)

print("\n== Summary (min / mean / max) ==")
for kind in ["correct", "swap one word", "drop one word", "different sentence"]:
    for metric, idx in [("words", 4), ("sounds", 5)]:
        vals = [r[idx] for r in rows if r[1] == kind]
        print(f"{kind:18} {metric:6} min {min(vals):.2f} mean {sum(vals) / len(vals):.2f} max {max(vals):.2f}")

# The app's rule: a word is right if Omnilingual heard it OR its sound error ratio <= WORD_SOUND_LIMIT.
LIMIT = 0.5
KINDS = ["correct", "swap one word", "drop one word", "different sentence"]


def bad_words(r):
    return [i for i, (heard, ratio) in enumerate(r[6]) if not (heard or ratio <= LIMIT)]


print(f"\n== Per-word rule (limit {LIMIT}): share of attempts that pass ==")
for kind in KINDS:
    group = [r for r in rows if r[1] == kind]
    print(f"{kind:18} {sum(not bad_words(r) for r in group) / max(1, len(group)):.0%}")

print("\n== Correct attempts that fail the per-word rule ==")
for r in rows:
    if r[1] == "correct" and bad_words(r):
        print(r[0], r[2], r[3], "bad word indexes", bad_words(r))
