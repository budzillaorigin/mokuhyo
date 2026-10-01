#!/usr/bin/env bash
# Records the SHA-256 and size of every built native library (native/build/<os>-<arch>/<variant>/) under
# "artifacts" in native/lock.json, keyed "<os>-<arch>/<variant>". Entries for platforms not built here are kept,
# so CI runners on each OS can each add theirs. Run by build.sh; safe to re-run.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
py="$(command -v python3 || command -v python)"
exec "$py" "$here/hash.py"
