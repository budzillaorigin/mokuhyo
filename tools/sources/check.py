#!/usr/bin/env python3
"""Lists every SOURCES.json row with its rights flags; fails when an acquired row has no license field (BRIEF_PHASE8 A-02).
Same as `fetch_sources.py --list`."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_sources import list_rows

if __name__ == "__main__":
    sys.exit(list_rows())
