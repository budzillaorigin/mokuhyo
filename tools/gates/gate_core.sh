#!/usr/bin/env bash
# gate_core (BRIEF §11.1): run on every phase, locally and in CI.
set -euo pipefail
cd "$(dirname "$0")/../.."
GRADLE="${GRADLE:-./gradlew}"
# The tools environment runs on the Python in tools/.python-version (CI run #15: uv picked 3.15 on macOS and
# mecab-python3 had no wheel). UV_PYTHON overrides any interpreter the caller's environment selects.
UV_PYTHON=$(tr -d '[:space:]' < tools/.python-version)
export UV_PYTHON
echo "== gate_core: Kotlin tests"
$GRADLE :shared:allTests :desktopApp:test --console=plain
echo "== gate_core: Python lint + tests"
(cd tools && uv run --locked ruff check . && uv run --locked --group content --group release python -W ignore run_tests.py)
echo "== gate_core: licenses"
(cd tools && uv run --locked python gates/check_licenses.py)
echo "== gate_core: model provenance"
(cd tools && uv run --locked python models/models_doc.py --check)
(cd tools && uv run --locked python gates/check_provenance.py)
echo "== gate_core: terms and sources (overlap gate)"
tools/gates/gate_terms.sh
echo "gate_core: PASS"
