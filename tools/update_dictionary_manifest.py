"""
Updates dictionaries/manifest.json after a dictionary build.

Always records when the dictionaries were last checked, so every scheduled run makes a commit
(which keeps GitHub from switching the schedule off). Only points the app at a new release when a
dictionary actually changed. Prints "publish=true" or "publish=false" for the workflow.
"""

import argparse
import json
import os

ap = argparse.ArgumentParser()
ap.add_argument("--built", required=True, help="languages.json from build_dictionaries.py")
ap.add_argument("--manifest", required=True)
ap.add_argument("--version", required=True)
ap.add_argument("--checked", required=True)
args = ap.parse_args()

built = json.load(open(args.built, encoding="utf-8"))
old = json.load(open(args.manifest, encoding="utf-8")) if os.path.exists(args.manifest) else {}
old_hashes = {code: info.get("sha256") for code, info in old.get("languages", {}).items()}
changed = {code: info["sha256"] for code, info in built.items()} != old_hashes

manifest = dict(old)
if changed:
    manifest.update(
        version=args.version,
        release=f"dictionaries-{args.version}",
        languages=built,
        source="English Wiktionary, via kaikki.org (Wiktextract)",
        license="CC BY-SA 4.0. Text from Wiktionary contributors; extracted with Wiktextract (Ylonen, LREC 2022).",
    )
manifest["checked"] = args.checked
os.makedirs(os.path.dirname(args.manifest), exist_ok=True)
with open(args.manifest, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, sort_keys=True)
    f.write("\n")
print(f"publish={'true' if changed else 'false'}")
