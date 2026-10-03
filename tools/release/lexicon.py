#!/usr/bin/env python3
"""Lexicon update packages (BRIEF_PHASE8 C-04, docs/LEXICON_FORMAT.md): build, sign and verify.

A package is `lexicon-<lang>-<domain>-<version>.json`: the full term set of one track for one language (not a diff;
the app computes the delta), a manifest, the attribution, and an Ed25519 signature over the canonical JSON of
everything except the signature. The public key ships in the app (tools/release/keys/ → desktopApp resources); the
private key never enters the repository.

  uv run --group release python release/lexicon.py keygen                 # once; private key to ~/.mokuhyo-keys/
  uv run --group release python release/lexicon.py build --language ja --version 1.1.0 [--previous 1.0.0] [--out dist/lexicon]
  uv run --group release python release/lexicon.py verify dist/lexicon/lexicon-ja-cuas-base-defense-1.1.0.json

The track sources (tools/tracks/<domain>.<lang>.json) are the single source of truth for the shipped track and for
the update channel.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
KEYS = TOOLS / "release" / "keys"
PUBLIC = KEYS / "lexicon-ed25519.pub.json"
PRIVATE = Path(os.environ.get("MOKUHYO_LEXICON_KEY", Path.home() / ".mokuhyo-keys" / "lexicon-ed25519.key"))
TRACKS = TOOLS / "tracks"
FORMAT = "mokuhyo-lexicon/1"
PUBLISHER = "Mokuhyo project"


def canonical(obj) -> bytes:
    """Canonical JSON: sorted keys, no whitespace, UTF-8, non-ASCII kept literal; integers, strings, booleans, null,
    arrays and objects only (no floats). The app re-encodes parsed JSON the same way to verify (CanonicalJson.kt)."""
    def check(x):
        if isinstance(x, float):
            raise TypeError("floats are not allowed in a lexicon package")
        if isinstance(x, dict):
            for v in x.values():
                check(v)
        elif isinstance(x, list):
            for v in x:
                check(v)
    check(obj)
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def key_id(public_raw: bytes) -> str:
    return hashlib.sha256(public_raw).hexdigest()[:16]


def cmd_keygen(_args) -> int:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    if PRIVATE.exists():
        print(f"keygen: {PRIVATE} already exists; refusing to overwrite")
        return 1
    sk = Ed25519PrivateKey.generate()
    raw_sk = sk.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    raw_pk = sk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    PRIVATE.parent.mkdir(parents=True, exist_ok=True)
    PRIVATE.write_text(base64.b64encode(raw_sk).decode() + "\n", encoding="utf-8")
    os.chmod(PRIVATE, 0o600)
    KEYS.mkdir(parents=True, exist_ok=True)
    PUBLIC.write_text(json.dumps({"keys": [{"keyId": key_id(raw_pk), "alg": "ed25519", "publisher": PUBLISHER,
                                            "publicKey": base64.b64encode(raw_pk).decode(),
                                            "created": dt.datetime.now(dt.UTC).date().isoformat()}]}, indent=1) + "\n", encoding="utf-8")
    print(f"keygen: private key {PRIVATE} (back it up; never commit it); public key {PUBLIC}")
    return 0


def sign(unsigned: dict, private_b64: str) -> dict:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    sk = Ed25519PrivateKey.from_private_bytes(base64.b64decode(private_b64))
    from cryptography.hazmat.primitives import serialization
    raw_pk = sk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    sig = sk.sign(canonical(unsigned))
    return dict(unsigned, signature={"alg": "ed25519", "keyId": key_id(raw_pk), "value": base64.b64encode(sig).decode()})


def package(terms: list[dict], lang: str, domain: str, version: str, attribution: str, previous: str | None, created: str | None = None) -> dict:
    manifest = {"id": f"lexicon-{lang}-{domain}", "lang": lang, "domain": domain, "version": version,
                "created": created or dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"), "publisher": PUBLISHER,
                "sha256": hashlib.sha256(canonical(terms)).hexdigest(), "terms": len(terms)}
    if previous:
        manifest["previousVersion"] = previous
    return {"format": FORMAT, "manifest": manifest, "attribution": attribution, "terms": terms}


def verify(pkg: dict, public_keys: list[dict]) -> tuple[bool, str]:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    if hashlib.sha256(canonical(pkg["terms"])).hexdigest() != pkg["manifest"]["sha256"]:
        return False, "terms do not match the manifest sha256"
    sig = pkg.get("signature")
    if not sig:
        return False, "unsigned"
    body = {k: v for k, v in pkg.items() if k != "signature"}
    for k in public_keys:
        if k["keyId"] == sig.get("keyId"):
            try:
                Ed25519PublicKey.from_public_bytes(base64.b64decode(k["publicKey"])).verify(base64.b64decode(sig["value"]), canonical(body))
                return True, f"signed by {k['publisher']} (key {k['keyId']})"
            except InvalidSignature:
                return False, "signature does not verify"
    return False, f"unknown key {sig.get('keyId')}"


def strip_floats(x):
    if isinstance(x, dict):
        return {k: strip_floats(v) for k, v in x.items()}
    if isinstance(x, list):
        return [strip_floats(v) for v in x]
    return int(x) if isinstance(x, float) and x.is_integer() else x


def cmd_build(args) -> int:
    track = json.loads((TRACKS / f"{args.domain}.{args.language}.json").read_text(encoding="utf-8"))
    pkg = package(strip_floats(track["terms"]), args.language, args.domain, args.version, track.get("attribution", ""), args.previous)
    if PRIVATE.exists():
        pkg = sign(pkg, PRIVATE.read_text(encoding="utf-8").strip())
    else:
        print(f"build: no private key at {PRIVATE}; the package is UNSIGNED (the app labels it 'unverified publisher')")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"lexicon-{args.language}-{args.domain}-{args.version}.json"
    f.write_text(json.dumps(pkg, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"build: {f} · {len(pkg['terms'])} terms · {'signed' if 'signature' in pkg else 'unsigned'}")
    return 0


def cmd_verify(args) -> int:
    pkg = json.loads(Path(args.file).read_text(encoding="utf-8"))
    keys = json.loads(PUBLIC.read_text(encoding="utf-8"))["keys"] if PUBLIC.exists() else []
    ok, why = verify(pkg, keys)
    print(f"verify: {'OK' if ok else 'FAIL'} — {why}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("keygen")
    b = sub.add_parser("build")
    b.add_argument("--language", required=True)
    b.add_argument("--domain", default="cuas-base-defense")
    b.add_argument("--version", required=True)
    b.add_argument("--previous")
    b.add_argument("--out", default=str(REPO / "dist" / "lexicon"))
    v = sub.add_parser("verify")
    v.add_argument("file")
    a = ap.parse_args()
    return {"keygen": cmd_keygen, "build": cmd_build, "verify": cmd_verify}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
