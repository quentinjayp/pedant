"""
promptfoo's side of the evals (see evals.py): the models have already translated, so the
"provider" for each model looks its translation up, and each one is scored against the reference.
"""

import json

_outputs = {}


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
    import sacrebleu

    reference = context["vars"]["reference"]
    score = sacrebleu.sentence_chrf(output, [reference]).score
    return {"pass": True, "score": score / 100, "reason": f"chrF {score:.1f} against: {reference}"}
