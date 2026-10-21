"""Inspect shipped pack metadata only; never reads tools/sources or source documents."""
import json
from pathlib import Path

repo = Path(__file__).resolve().parents[3]
rows = []
for lang in "ja es fr de pt-BR ru zh-Hans ko ar fa id".split():
    def read(name):
        return json.loads((repo / "content/packs" / lang / f"{name}.json").read_text())

    exam = read("exam")
    track = read("track-cuas-base-defense")
    rows.append({
        "language": lang,
        "reading_passages": len(exam["reading"]["passages"]),
        "listening_passages": len(exam["listening"]["passages"]),
        "formats": sorted({p.get("format", "plain") or "plain" for p in exam["reading"]["passages"]}),
        "terms": len(track["terms"]), "drills": len(track["drills"]),
        "track_scenarios": len(track["scenarios"]),
        "personas": len(read("personas")["personas"]),
        "role_plays": len(read("opi")["rolePlays"]),
        "exemplars": len(read("exemplars")["exemplars"]),
        "exemplar_levels": sorted({e["level"] for e in read("exemplars")["exemplars"]}),
        "official_links": len(read("feeds")["feeds"]),
        "culture_cards": len(read("culture")["cards"]),
    })
print(json.dumps(rows, ensure_ascii=False, indent=2))
