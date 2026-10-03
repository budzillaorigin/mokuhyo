# Prompt: accuracy check against an allied source (pipeline step 4a)

Use only with sources where `machine_extract_ok` is `true` in SOURCES.json (currently: public US doctrine, AFCLC guides, Japan white papers, Brazil MD35-G-01, ROK 2022 white paper, France RNS 2025, NATO AAP-06). For `false` or `unclear` sources, a human reviewer does this check by reading the page cited in `term_source_page`.

---

You are checking a drafted definition for accuracy. You are not rewriting it.

**Language:** {lang}
**Term:** {term}
**Drafted definition:** {definition}
**US English definition (meaning of record):** {definition_en}

**How the term is used in {term_source_id}, p. {term_source_page}:**
{excerpt}

Answer in JSON only:
```
{
  "seed_id": "{seed_id}",
  "lang": "{lang}",
  "term_matches_source_usage": true | false,
  "definition_consistent_with_us_meaning": true | false,
  "definition_consistent_with_source_usage": true | false,
  "problems": ["short description of each problem, or empty"],
  "verdict": "pass" | "fix" | "escalate"
}
```

- `fix` = small wording problem the drafter can correct.
- `escalate` = the allied usage and the US meaning differ in substance (different scope, different doctrine). A human decides; do not resolve it yourself.
- Do not include text copied from the excerpt in your answer.
