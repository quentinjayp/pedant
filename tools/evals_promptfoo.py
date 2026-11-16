"""
promptfoo's side of the evals (see evals.py): the models have already translated, so the
"provider" for each model looks its translation up, and each one is scored against the reference.
"""

import json
import re

_outputs = {}

# Serbian is written in two alphabets. The references are in Cyrillic and the models write Latin,
# so a Latin answer is compared with the reference in Latin letters.
_SERBIAN = dict(zip("абвгдђежзијклмнопрстћуфхцчш", "a b v g d đ e ž z i j k l m n o p r s t ć u f h c č š".split()))
_SERBIAN.update({"љ": "lj", "њ": "nj", "џ": "dž"})
_SERBIAN.update({k.upper(): v.capitalize() for k, v in list(_SERBIAN.items())})


def score(output, reference, code):
    """chrF (0 to 100) of a model's translation against the reference, for the language [code]."""
    import sacrebleu

    if code == "sr" and not re.search("[\u0400-\u04ff]", output):
        reference = "".join(_SERBIAN.get(c, c) for c in reference)
    return sacrebleu.sentence_chrf(output, [reference]).score


def labelled(raw):
    """The text after SAY: in a model's answer, up to the next label; empty if it has no SAY: label."""
    m = re.search(r"SAY:(.*?)(?=\n?(?:REPLY|ENGLISH|SAY):|\Z)", raw, re.S)
    return m.group(1).strip() if m else ""


def said(raw):
    """The phrase in a model's answer, as the app reads it (Tutor.parseSay): the text after SAY:, or,
    when the label is left out or written another way ("САЙ:"), the answer without a short label at
    its start. Reasoning a model writes out first (<think>...</think>) isn't part of it."""
    raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
    return labelled(raw) or re.sub(r"^\w{1,10}\s*:\s*", "", raw).strip()


def replied(raw):
    """The translation in an answer about the tutor's reply, as the app reads it (Tutor.parse): the
    text after ENGLISH:, up to the next label; empty if it has no ENGLISH: label. The label is
    ENGLISH: whatever the learner's own language is, as the app's instruction says."""
    raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S)
    m = re.search(r"ENGLISH:(.*?)(?=\n?(?:REPLY|ENGLISH|SAY):|\Z)", raw, re.S)
    return m.group(1).strip() if m else ""


def answer(kind, raw):
    """What the app would take from an answer: the phrase to say ("line": the learner's own line
    translated) or the translation of the tutor's reply ("reply")."""
    return said(raw) if kind == "line" else replied(raw)


def parts(key):
    """A translation's key, "<sentence id>|<kind>|<from>><to>", as (sentence id, kind, from, to)."""
    sentence, kind, pair = key.split("|")
    source, target = pair.split(">")
    return sentence, kind, source, target


def lookup(prompt, options, context):
    """A model's translation for this test's sentence and pair of languages."""
    cfg = options["config"]
    if cfg["outputs"] not in _outputs:
        _outputs[cfg["outputs"]] = json.load(open(cfg["outputs"], encoding="utf-8"))
    key = context["vars"]["key"]
    out = _outputs[cfg["outputs"]].get(cfg["model"], {}).get(key)
    if out is None:
        return {"output": "(not translated)"}
    return {"output": answer(parts(key)[1], out["raw"]) or "(nothing)"}


def chrf(output, context):
    """chrF against the reference, as a score from 0 to 1. Never a failure: the evals only report."""
    reference = context["vars"]["reference"]
    points = score(output, reference, parts(context["vars"]["key"])[3])
    return {"pass": True, "score": points / 100, "reason": f"chrF {points:.1f} against: {reference}"}
