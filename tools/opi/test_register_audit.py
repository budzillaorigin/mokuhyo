"""Tests for register_audit.py (QC 2026-10-30 V9). Run: python opi/test_register_audit.py"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import register_audit


def test_a_seeded_mismatch_is_flagged() -> None:
    pack = {"questions": [{"id": "x-1", "prompt": "¿Cómo te llamas?"}, {"id": "x-2", "prompt": "¿Cómo se llama usted?"}]}
    assert [h["id"] for h in register_audit.audit_pack("es", pack)] == ["x-1"]
    ru = {"questions": [{"id": "r-1", "prompt": "Как тебя зовут?"}, {"id": "r-2", "prompt": "Как вас зовут?"}]}
    assert [h["id"] for h in register_audit.audit_pack("ru", ru)] == ["r-1"]
    ja = {"questions": [{"id": "j-1", "prompt": "名前は何？"}, {"id": "j-2", "prompt": "お名前は何ですか？"}]}
    assert [h["id"] for h in register_audit.audit_pack("ja", ja)] == ["j-1"]


def test_role_play_openings_are_exempt() -> None:
    pack = {"questions": [], "rolePlays": [{"id": "rp", "opening": "¡Hola! ¿Qué tal estás?"}]}
    assert register_audit.audit_pack("es", pack) == []


def test_the_two_confirmed_warmups_use_the_profile_register() -> None:
    """QC 2026-10-30 V9: the first Spanish and Russian warm-ups used tú/ты under a usted/вы profile."""
    hits = {h["id"] for h in register_audit.audit(register_audit.ROOT / "content" / "packs")}
    assert "es-q-warmup-0+-01" not in hits and "ru-q-warmup-0+-01" not in hits, sorted(hits)


if __name__ == "__main__":
    for t in [test_a_seeded_mismatch_is_flagged, test_role_play_openings_are_exempt, test_the_two_confirmed_warmups_use_the_profile_register]:
        t()
        print("ok", t.__name__)
