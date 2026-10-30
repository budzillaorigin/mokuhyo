"""Offline check for listening_kit.py (run by tools/run_tests.py): sheets join to the key, qualities stay separate,
blank scores are skipped and out-of-range scores are refused."""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import listening_kit


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        kit = Path(tmp)
        (kit / "key.csv").write_text(
            "file,language,source,voice,prompt_id\nfa/fa-001.wav,fa,piper,fa_IR-amir-medium,q1\nfa/fa-002.wav,fa,prerendered,chatterbox:x,q1\n",
            encoding="utf-8")
        a = kit / "rater-a.csv"
        a.write_text("file,language,intelligibility_1to5,pronunciation_1to5,naturalness_1to5,comments\n"
                     "fa/fa-001.wav,fa,4,2,3,stress on the last word\nfa/fa-002.wav,fa,5,,4,\n", encoding="utf-8")
        b = kit / "rater-b.csv"
        b.write_text("file,language,intelligibility_1to5,pronunciation_1to5,naturalness_1to5,comments\nfa/fa-001.wav,fa,2,4,3,\n", encoding="utf-8")
        totals, comments = listening_kit.score(listening_kit.read_key(kit), [a, b])
        piper = totals[("fa", "piper fa_IR-amir-medium")]
        assert piper["intelligibility_1to5"] == [4.0, 2.0] and piper["pronunciation_1to5"] == [2.0, 4.0], piper
        assert "pronunciation_1to5" not in totals[("fa", "prerendered chatterbox:x")], "blank scores are skipped"
        assert comments[("fa", "piper fa_IR-amir-medium")] == ["fa/fa-001.wav: stress on the last word"]
        md = listening_kit.markdown(totals, comments)
        assert "| fa | piper fa_IR-amir-medium | 3.00 (n=2) | 3.00 (n=2) | 3.00 (n=2) |" in md, md
        c = kit / "rater-c.csv"
        c.write_text("file,language,naturalness_1to5,cadence_1to5,continuity_1to5,comments\nfa/fa-002.wav,fa,4,2,5,\n", encoding="utf-8")
        totals, _ = listening_kit.score(listening_kit.read_key(kit), [c])
        conn = totals[("fa", "prerendered chatterbox:x")]
        assert conn["cadence_1to5"] == [2.0] and conn["continuity_1to5"] == [5.0] and conn["naturalness_1to5"] == [4.0], conn
        totals, comments = listening_kit.score(listening_kit.read_key(kit), [a, b])
        (kit / "missing.csv").write_text("language,source,voice,prompt_id,reason\nfa,piper,fa_IR-gyro-medium,q1,fell back to piper:fa_IR-amir-medium\n",
                                         encoding="utf-8")
        md = listening_kit.markdown(totals, comments, listening_kit.read_missing(kit))
        assert "- fa / piper fa_IR-gyro-medium / q1: fell back to piper:fa_IR-amir-medium" in md, md
        bad = kit / "bad.csv"
        bad.write_text("file,language,intelligibility_1to5,pronunciation_1to5,naturalness_1to5,comments\nfa/fa-001.wav,fa,7,,,\n", encoding="utf-8")
        try:
            listening_kit.score(listening_kit.read_key(kit), [bad])
            raise AssertionError("a score of 7 must be refused")
        except ValueError:
            pass
    print("test_listening_kit: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
