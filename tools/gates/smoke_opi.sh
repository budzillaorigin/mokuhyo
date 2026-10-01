#!/usr/bin/env bash
# Phase 1 gate extra (BRIEF §11.2): the packaged app loads the Tier A model from a local fixture path and completes one
# Japanese OPI turn with synthesized audio in (OS voice) and a transcript out. Needs the ~2.5 GB Tier A GGUF and a
# Whisper model; both are fetched into tools/.cache/models (hash-verified) when missing. macOS/Windows (OS voice).
set -euo pipefail
cd "$(dirname "$0")/../.."
CACHE=tools/.cache/models
(cd tools && uv run --locked python - <<'PY'
import json, sys
sys.path.insert(0, "release")
from stage_resources import fetch, CACHE
m = {x["id"]: x for x in json.load(open("../content/models/manifest.json"))["models"]}
for mid in ("phi-4-mini-instruct-q4km", "whisper-small"):
    f = m[mid]["files"][0]
    fetch(f["url"], CACHE / f["name"], f["sha256"], f["bytes"])
PY
)
case "$(uname -s)" in
  Darwin) BIN=desktopApp/build/compose/binaries/main/app/Mokuhyo.app/Contents/MacOS/Mokuhyo ;;
  MINGW*|MSYS*|CYGWIN*) BIN=desktopApp/build/compose/binaries/main/app/Mokuhyo/Mokuhyo.exe ;;
  *) BIN=desktopApp/build/compose/binaries/main/app/mokuhyo/bin/mokuhyo ;;
esac
"$BIN" --smoke-opi --model "$PWD/$CACHE/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf" --whisper "$PWD/$CACHE/ggml-small.bin" "$@"
