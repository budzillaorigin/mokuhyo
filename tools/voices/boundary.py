"""Boundary timing policy for joined clips (placeholder until V2 replaces the fixed pauses).

POLICY_VERSION identifies the timing rules a render used; it is part of every render identity (render_identity.py), so
changing the rules invalidates cached renders.
"""
from __future__ import annotations

# The fixed pauses the renderers used before QC 2026-10-30: 250 ms between chunks, the job's pause after every line.
POLICY_VERSION = "legacy-fixed-1"
