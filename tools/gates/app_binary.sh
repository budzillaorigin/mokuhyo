#!/usr/bin/env bash
# Prints the packaged app's launcher (createDistributable output) for this OS.
set -euo pipefail
cd "$(dirname "$0")/../.."
APP=desktopApp/build/compose/binaries/main/app
case "$(uname -s)" in
  Darwin) found=$(ls "$APP"/*.app/Contents/MacOS/* 2>/dev/null | head -1) ;;
  MINGW*|MSYS*|CYGWIN*) found=$(ls "$APP"/*/*.exe 2>/dev/null | head -1) ;;
  *) found=$(ls "$APP"/*/bin/* 2>/dev/null | grep -v '\.so$' | head -1) ;;
esac
[ -n "${found:-}" ] || { echo "no packaged launcher under $APP" >&2; exit 1; }
echo "$found"
