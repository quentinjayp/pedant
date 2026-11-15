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


def lenient(raw):
    """The translation in an answer that left out the SAY: label or wrote it another way: the
    answer without anything it wrote out first as reasoning, and without a short label at its start."""
    raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
    return re.sub(r"^\w{1,10}\s*:\s*", "", raw).strip()


def lookup(prompt, options, context):
    """A model's translation for this test's sentence and language."""
    cfg = options["config"]
    if cfg["outputs"] not in _outputs:
        _outputs[cfg["outputs"]] = json.load(open(cfg["outputs"], encoding="utf-8"))
    out = _outputs[cfg["outputs"]].get(cfg["model"], {}).get(context["vars"]["key"])
    if out is None:
        return {"output": "(not translated)"}
    return {"output": out["say"] or f"(no phrase in the answer) {out['raw'][:300]}"}


def chrf(output, context):
    """chrF against the reference, as a score from 0 to 1. Never a failure: the evals only report."""
    reference = context["vars"]["reference"]
    points = score(output, reference, context["vars"]["key"].rsplit("|", 1)[1])
    return {"pass": True, "score": points / 100, "reason": f"chrF {points:.1f} against: {reference}"}
