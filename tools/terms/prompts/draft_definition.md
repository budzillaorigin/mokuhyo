# Prompt: draft a target-language definition (pipeline step 3)

Fill the {placeholders}. Send one term per call, or a small batch with the same structure repeated.

---

You are drafting a learner-facing definition for a military terminology app.

**Target language:** {lang}
**Target term (already chosen, do not change it):** {term}
**Where the term was confirmed:** {term_source_id}, p. {term_source_page} (or "model-proposed, unconfirmed")

**Authoritative English definition (US doctrine, public domain):**
> {definition_en}
> Source: {source_doc} p. {source_page}

**Instructions**

1. Write the definition in {lang} for an intermediate learner, 1–2 sentences.
2. Base the meaning only on the English definition above. Translate and simplify it; you may restructure it.
3. Use {term} exactly as given. If the English definition uses other technical terms, render them with standard {lang} military usage.
4. Any reference excerpts provided below are for checking terminology only. Do not quote them, and do not closely paraphrase their sentences. Every sentence you write must be your own wording.
5. If you think {term} is the wrong term for this concept in {lang} military usage, still write the definition, then add a line starting `TERM-CONCERN:` explaining why and suggesting an alternative. Do not silently substitute.
6. Output JSON only:
   `{"seed_id": "{seed_id}", "lang": "{lang}", "term": "{term}", "definition": "...", "term_concern": null}`

**Reference excerpts (terminology check only — do not reuse wording):**
{optional_excerpts — include only from sources with machine_extract_ok = true}
