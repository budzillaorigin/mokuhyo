"""Lexicon package tests (C-04): canonical JSON, sign/verify, tamper detection, and the cross-language fixture the
Kotlin verifier reads (shared/src/jvmTest/resources/lexicon/). `python test_lexicon.py --write-fixture` rewrites it."""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import lexicon as L

FIXTURE = HERE.parents[1] / "shared" / "src" / "jvmTest" / "resources" / "lexicon"
# A throwaway test key (never used for real packages): 32 fixed bytes.
TEST_SK = base64.b64encode(bytes(range(1, 33))).decode()


def term(i: int, changed: bool = False) -> dict:
    return {"id": f"cuas-{i:03d}", "domain": "cuas", "priority": 1 + i % 3, "termEn": f"term {i}", "acronym": "",
            "definitionEn": f"English definition {i}.", "definitionEnSource": {"doc": "DoD Dictionary (Aug 2026)", "page": str(i), "sourceId": "us-dod-dict-2026-08"},
            "term": f"用語{i}" + ("（改）" if changed else ""), "termKind": "calque", "radioEnglish": False,
            "equivalents": [{"text": f"用語{i}", "kind": "calque", "source": "", "page": "", "verified": False}],
            "definition": f"定義 {i}「引用」\\n改行\t" + ("変更" if changed else ""), "status": "checked", "badge": "unconfirmed-term",
            "registerNote": "", "examples": [], "collocations": [], "source": "llm", "verified": False}


def public_of(sk_b64: str) -> dict:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    pk = Ed25519PrivateKey.from_private_bytes(base64.b64decode(sk_b64)).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return {"keyId": L.key_id(pk), "alg": "ed25519", "publisher": "Test publisher", "publicKey": base64.b64encode(pk).decode()}


def fixtures() -> tuple[dict, dict]:
    v1 = L.sign(L.package([term(i) for i in range(1, 31)], "ja", "cuas-base-defense", "1.0.0", "Test attribution — ©", None,
                          created="2026-10-03T00:00:00Z"), TEST_SK)
    v2_terms = [term(i, changed=i <= 5) for i in range(1, 31)] + [term(i) for i in range(31, 51)]
    v2 = L.sign(L.package(v2_terms, "ja", "cuas-base-defense", "1.1.0", "Test attribution — ©", "1.0.0", created="2026-10-04T00:00:00Z"), TEST_SK)
    return v1, v2


def test_roundtrip() -> None:
    v1, v2 = fixtures()
    keys = [public_of(TEST_SK)]
    assert L.verify(v1, keys)[0] and L.verify(v2, keys)[0]
    bad = json.loads(json.dumps(v2))
    bad["terms"][0]["term"] = "tampered"
    assert L.verify(bad, keys) == (False, "terms do not match the manifest sha256")
    bad = json.loads(json.dumps(v2))
    bad["manifest"]["version"] = "9.9.9"
    assert not L.verify(bad, keys)[0]
    unsigned = {k: v for k, v in v1.items() if k != "signature"}
    assert L.verify(unsigned, keys) == (False, "unsigned")
    assert L.canonical({"b": 1, "a": "é\n\"x\""}) == '{"a":"é\\n\\"x\\"","b":1}'.encode()
    try:
        L.canonical({"x": 1.5})
        raise AssertionError("floats must be refused")
    except TypeError:
        pass


def test_fixture_current() -> None:
    v1, v2 = fixtures()
    for name, pkg in (("v1.json", v1), ("v2.json", v2)):
        f = FIXTURE / name
        assert f.exists() and json.loads(f.read_text(encoding="utf-8")) == pkg, f"{f} is stale: run test_lexicon.py --write-fixture"


if __name__ == "__main__":
    if "--write-fixture" in sys.argv:
        FIXTURE.mkdir(parents=True, exist_ok=True)
        v1, v2 = fixtures()
        (FIXTURE / "v1.json").write_text(json.dumps(v1, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        (FIXTURE / "v2.json").write_text(json.dumps(v2, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        (FIXTURE / "test-key.pub.json").write_text(json.dumps({"keys": [public_of(TEST_SK)]}, indent=1) + "\n", encoding="utf-8")
        print("fixtures written")
    for t in (test_roundtrip, test_fixture_current):
        t()
        print("ok", t.__name__)
