"""
The band and scenario evals (docs/plans/levels-and-scenarios.md, tasks 4 and 15): Gemma holds
conversations as the app would (its instructions, the scenario's role and setting, the shipped
opening), and Claude, in a session, plays the learner and judges the replies. Gemma can't do either.

    python3 tools/band_conversations.py setup                       # one conversation per band per language
    python3 tools/band_conversations.py round lines.json model.litertlm

`round` sends each conversation in lines.json ({"fr-Basic": "the learner's next line", ...}) its line
and adds Gemma's reply. The conversations are in evals/bands/, with Claude's judgments in
evals/bands/judgments.md.
"""
import json, os, re, sys, time
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
APP = f"{ROOT}/app/src/main/assets"
CONV = f"{ROOT}/evals/bands"
ins = json.load(open(f"{APP}/instructions.json", encoding="utf-8"))
scen = {s["id"]: s for s in json.load(open(f"{APP}/scenarios.json", encoding="utf-8"))["scenarios"]}
langs = json.load(open(f"{APP}/languages.json", encoding="utf-8"))["languages"]
openings = json.load(open(f"{APP}/openings.json", encoding="utf-8"))["openings"]

def fill(text, language, own="English"):
    an = ("an " if own[0] in "AEIO" else "a ") + own
    return text.replace("{language}", language).replace("{anOwn}", an).replace("{own}", own)

def with_band(text, band):
    b = ins["bands"][band]
    return text.replace("{band}", b["band"]).replace("{reply}", b["reply"]).replace("{translation}", b["translation"]).replace("{translationGuide}", b["translationGuide"])

def system(lang, sid, band):
    """Instructions.system, as the app builds it."""
    if sid == "none":
        opening = fill(ins["openDefault"], lang["englishName"])
    else:
        s = scen[sid]
        opening = fill(ins["openRole"], lang["englishName"]).replace("{role}", s["role"]).replace("{setting}", s["settings"][lang["code"]])
    return fill(with_band(ins["learning"], band), lang["englishName"]) + "\n\n" + opening

def reply_of(raw):
    m = re.search(r"REPLY:\s*(.*)", raw, re.S)
    return (m.group(1) if m else raw).strip()

def setup():
    os.makedirs(CONV, exist_ok=True)
    for i, l in enumerate(langs):
        for band in ("Basic", "Independent"):
            ids = ["none"] + [s for s in scen if band in scen[s]["bands"]]
            sid = ids[(i * 7 + (3 if band == "Independent" else 0)) % len(ids)]
            reg = ("Formal", "Casual")[(i + (band == "Independent")) % 2]
            o = openings[f"{l['code']}-en-{sid}-{reg}-{band}"]
            conv = {"language": l["code"], "name": l["englishName"], "band": band, "scenario": sid, "register": reg,
                    "turns": [{"learner": None, "sent": f"[START {reg.lower()}]", "reply": o["reply"], "english": o["english"]}]}
            json.dump(conv, open(f"{CONV}/{l['code']}-{band}.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    used = {}
    for f in sorted(os.listdir(CONV)):
        if f.endswith(".json"):
            c = json.load(open(f"{CONV}/{f}")); used.setdefault(c["band"], set()).add(c["scenario"])
    for b, u in used.items():
        print(b, len(u), "scenarios used; missing:", sorted((set(s for s in scen if b in scen[s]["bands"]) | {"none"}) - u))

def round_(lines_path, model):
    import litert_lm
    lines = json.load(open(lines_path, encoding="utf-8"))
    engine = litert_lm.Engine(model, backend=litert_lm.Backend.CPU(), max_num_tokens=4096)
    for f, line in lines.items():
        p = f"{CONV}/{f}.json"
        conv = json.load(open(p, encoding="utf-8"))
        lang = next(l for l in langs if l["code"] == conv["language"])
        history = []
        for t in conv["turns"]:
            history += [{"role": "user", "content": t["sent"]}, {"role": "model", "content": "REPLY: " + t["reply"]}]
        sent = f"[SAID {conv['register'].lower()}] {line}"
        sampler = litert_lm.SamplerConfig(top_k=40, top_p=0.95, temperature=0.7, seed=len(conv["turns"]))
        started = time.time()
        with engine.create_conversation(system_message=system(lang, conv["scenario"], conv["band"]), messages=history, sampler_config=sampler) as c:
            out = c.send_message({"role": "user", "content": sent})
        content = out.get("content")
        raw = "".join(x.get("text", "") for x in content if isinstance(x, dict)) if isinstance(content, list) else str(content)
        conv["turns"].append({"learner": line, "sent": sent, "raw": raw, "reply": reply_of(raw), "seconds": round(time.time() - started, 1)})
        json.dump(conv, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f, conv["turns"][-1]["seconds"], reply_of(raw), flush=True)

if __name__ == "__main__":
    if sys.argv[1] == "setup": setup()
    else: round_(sys.argv[2], sys.argv[3])
