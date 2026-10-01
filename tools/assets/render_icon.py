"""Renders the app icon (a target: concentric rings and a center dot) to desktopApp/icons/ as PNG, ICO and ICNS.

    uv run --group assets python assets/render_icon.py

Original artwork, CC BY-SA 4.0 like the rest of Mokuhyo's content. No product or program names in the icon.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[2] / "desktopApp" / "icons"
NAVY = (27, 42, 74, 255)
CREAM = (244, 239, 228, 255)
VERMILION = (214, 69, 51, 255)


def render(size: int) -> Image.Image:
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = s * 0.06
    d.rounded_rectangle([pad, pad, s - pad, s - pad], radius=s * 0.2, fill=NAVY)
    c = s / 2
    for r, color in [(0.36, CREAM), (0.29, NAVY), (0.22, CREAM), (0.15, NAVY), (0.08, VERMILION)]:
        rr = s * r
        d.ellipse([c - rr, c - rr, c + rr, c + rr], fill=color)
    return img.resize((size, size), Image.Resampling.LANCZOS)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    big = render(1024)
    big.save(OUT / "mokuhyo.png")
    big.save(OUT / "mokuhyo.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    if sys.platform == "darwin":
        with tempfile.TemporaryDirectory() as tmp:
            iconset = Path(tmp) / "mokuhyo.iconset"
            iconset.mkdir()
            for px in (16, 32, 128, 256, 512):
                render(px).save(iconset / f"icon_{px}x{px}.png")
                render(px * 2).save(iconset / f"icon_{px}x{px}@2x.png")
            subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(OUT / "mokuhyo.icns")], check=True)
    else:
        big.save(OUT / "mokuhyo.icns")
    print("icons written to", OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
