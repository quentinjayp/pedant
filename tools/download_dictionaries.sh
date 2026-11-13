#!/usr/bin/env bash
# Downloads the published dictionaries (the release named in dictionaries/manifest.json) into a
# folder, as <code>.db.
#
#     tools/download_dictionaries.sh <dir>
set -euo pipefail
dir=${1:?usage: download_dictionaries.sh <dir>}
manifest="$(dirname "$0")/../dictionaries/manifest.json"
mkdir -p "$dir"
release=$(python3 -c "import json, sys; print(json.load(open(sys.argv[1]))['release'])" "$manifest")
for code in $(python3 -c "import json, sys; print(' '.join(json.load(open(sys.argv[1]))['languages']))" "$manifest"); do
  curl -fsSL --retry 3 "https://github.com/${GITHUB_REPOSITORY:-quentinjayp/pedant}/releases/download/$release/$code.db.gz" \
    | gunzip > "$dir/$code.db"
done
ls -l "$dir"
