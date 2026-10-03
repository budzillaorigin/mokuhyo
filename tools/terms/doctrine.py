"""Parsers for the public-domain US doctrine glossaries (verbatim_ok = true) used by the seed list and terms_en.json.

Each parser returns `Entry` records with the definition text exactly as printed (line breaks joined, hyphenation at
line ends repaired), the PDF page index and the printed page label. Only sources that `sources.require(id, "parse")`
allows are read; `us-limited/` is refused there.

  - DoD Dictionary (Aug 2026 / Jun 2025):  "term — Definition. Also called X. (JP 3-01)"
  - ATP 3-01.81 glossary section II:        term on its own line, indented definition, "(JP 3-30)"
  - JP 3-10 / JP 3-01 glossary part II:     "term. Definition. (DOD Dictionary. Source: JP 3-10)"
  - ATP 1-02.1 brevity table:               "BANDIT    [A/A] An identified aircraft ..." (two columns)
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

import sources as S


@dataclass
class Entry:
    term: str
    definition: str
    source_id: str
    pdf_page: int  # 1-based PDF page
    page_label: str  # printed page number, or the PDF page when none is printed
    acronym: str = ""
    ref: str = ""  # doctrinal reference at the end, e.g. "JP 3-01"
    tags: list[str] = field(default_factory=list)


def key(term: str) -> str:
    """Comparison key: case-folded, punctuation and parentheses removed, single spaces."""
    t = unicodedata.normalize("NFKC", term).lower().replace("’", "'")
    t = re.sub(r"\(.*?\)", " ", t)
    t = re.sub(r"[^a-z0-9/+' -]", " ", t)
    return " ".join(t.replace("-", " ").split())


def _label(page: str, pdf_index: int) -> str:
    """The printed page number from the running footer (left or right end; years are skipped)."""
    lines = [ln.strip() for ln in page.strip().splitlines() if ln.strip()]
    for ln in reversed(lines[-3:]):
        toks = ln.split()
        for tok in (toks[-1], toks[0]) if toks else ():
            if re.fullmatch(r"[A-Z]{1,3}-\d{1,3}|[ivxlc]{1,6}|\d{1,4}", tok) and not re.fullmatch(r"(19|20)\d\d", tok):
                return tok
    return str(pdf_index + 1)


def _join(lines: list[str]) -> str:
    out = ""
    for ln in lines:
        ln = ln.strip()
        if not ln:
            continue
        if out.endswith("-") and ln[:1].islower() and not out.endswith(" -"):
            out = out[:-1] + ln
        else:
            out = (out + " " + ln) if out else ln
    return " ".join(out.split())


_REF = re.compile(r"\((?:DOD Dictionary\. Source: )?((?:JP|ATP|ADP|FM|AFDP|ADRP|DODD|DODI|DoDD|DoDI|AR|AFI|JP\s)[^()]*|Approved for[^()]*)\)\s*$")
_ALSO = re.compile(r"Also called ([A-Z0-9][A-Za-z0-9/&-]*(?: or [A-Z0-9][A-Za-z0-9/&-]*)?)\.")


def _finish(term: str, body: str, sid: str, pdf: int, label: str) -> Entry:
    m = _REF.search(body)
    ref = m.group(1).strip() if m else ""
    also = _ALSO.search(body)
    return Entry(term=" ".join(term.split()), definition=body, source_id=sid, pdf_page=pdf + 1, page_label=label,
                 acronym=also.group(1) if also else "", ref=ref)


def dod_dictionary(source_id: str = "us-dod-dict-2026-08", pages: list[str] | None = None) -> list[Entry]:
    """DoD Dictionary entries; [pages] = page texts to parse instead of the cached source (fixtures, new editions)."""
    pages = pages if pages is not None else S.pages(source_id)
    entries: list[Entry] = []
    cur: tuple[str, list[str], int, str] | None = None
    started = False
    head = re.compile(r"^(\S[^—]{0,110}?) ?— ?(\S.*)$")
    for i, page in enumerate(pages):
        if "terms and definitions" in page[:400].lower():
            started = True
        if not started:
            continue
        if "Appendix A" in page[:300] or "Abbreviations, Acronyms" in page[:400]:
            break
        label = _label(page, i)
        for raw in page.splitlines():
            if not raw.strip() or raw.strip() == label or "Terms and Definitions" in raw:
                continue
            m = head.match(raw) if not raw.startswith(" ") else None
            if m:
                if cur:
                    entries.append(_finish(cur[0], _join(cur[1]), source_id, cur[2], cur[3]))
                cur = (m.group(1), [m.group(2)], i, label)
            elif cur and raw.startswith(" ") and not _REF.search(_join(cur[1])):
                cur[1].append(raw)  # continuation; nothing is appended once the entry's reference has closed it
    if cur:
        entries.append(_finish(cur[0], _join(cur[1]), source_id, cur[2], cur[3]))
    return [e for e in entries if e.definition and not e.term.isupper()]


def dod_acronyms(source_id: str = "us-dod-dict-2026-08") -> dict[str, list[str]]:
    """Appendix A: acronym → expansions."""
    pages = S.pages(source_id)
    out: dict[str, list[str]] = {}
    started = False
    for page in pages:
        if "Abbreviations, Acronyms" in page[:600] or "Appendix A" in page[:300]:
            started = True
        if not started:
            continue
        for ln in page.splitlines():
            m = re.match(r"^\s{0,4}([A-Z0-9][A-Z0-9/&()+-]{1,14})\s{2,}(\S.+)$", ln)
            if m:
                out.setdefault(m.group(1), []).append(" ".join(m.group(2).split()))
    return out


def atp_glossary(source_id: str = "us-atp-3-01-81") -> list[Entry]:
    pages = S.pages(source_id)
    entries: list[Entry] = []
    in_terms = False
    cur: list = []
    for i, page in enumerate(pages):
        if "SECTION II" in page.upper() and "TERMS" in page.upper():
            in_terms = True
        if not in_terms or "Glossary" not in page[:300]:
            continue
        label = _label(page, i)
        for raw in page.splitlines():
            s = raw.rstrip()
            if not s.strip() or re.search(r"\d{4}\s+ATP|ATP [\d.-]+\s+\d|^\s*Glossary\s*$|SECTION II|TERMS", s):
                continue
            indent = len(s) - len(s.lstrip())
            if indent == 0 and len(s) < 90:
                if cur and cur[1]:
                    entries.append(_finish(cur[0], _join(cur[1]), source_id, cur[2], cur[3]))
                cur = [s.strip(), [], i, label]
            elif cur:
                cur[1].append(s)
        if "REFERENCES" in page.upper()[:200]:
            break
    if cur and cur[1]:
        entries.append(_finish(cur[0], _join(cur[1]), source_id, cur[2], cur[3]))
    return entries


def jp_glossary(source_id: str) -> list[Entry]:
    """Joint publication glossary part II: "term. Definition. (source)"."""
    pages = S.pages(source_id)
    entries: list[Entry] = []
    in_terms = False
    cur: list = []
    head = re.compile(r"^([a-z0-9][^.]{1,100}?)\.\s+(\S.*)$")
    for i, page in enumerate(pages):
        up = page.upper()
        if "PART II" in up and "TERMS AND DEFINITIONS" in up:
            in_terms = True
        if not in_terms or "Glossary" not in page[:300]:
            continue
        label = _label(page, i)
        for raw in page.splitlines():
            if not raw.strip() or "Glossary" in raw or "PART II" in raw.upper() or re.match(r"^\s*(GL-\d+|JP [\d-]+)\s*$", raw.strip()):
                continue
            m = head.match(raw) if not raw.startswith(" ") else None
            if m:
                if cur:
                    entries.append(_finish(cur[0], _join(cur[1]), source_id, cur[2], cur[3]))
                cur = [m.group(1), [m.group(2)], i, label]
            elif cur and raw.startswith(" "):
                cur[1].append(raw)
    if cur:
        entries.append(_finish(cur[0], _join(cur[1]), source_id, cur[2], cur[3]))
    return entries


def brevity(source_id: str = "us-atp-1-02-1") -> list[Entry]:
    """Multi-service brevity codes: the code word, its bracketed tags and the definition."""
    pages = S.pages(source_id)
    entries: list[Entry] = []
    cur: list = []
    head = re.compile(r"^([A-Z][A-Z0-9 ()/'’,.-]{1,40}?(?: \[[a-z ,/]+\])?)\s{2,}(.+)$|^([A-Z][A-Z0-9 ()/'’-]{1,30} \[[a-z ,/]+\]) (\S.+)$")
    for i, page in enumerate(pages):
        if "Brevity Codes" not in page[:300] and "Table 2" not in page[:300]:
            if cur:
                entries.append(_brev(cur, source_id))
                cur = []
            continue
        label = _label(page, i)
        for raw in page.splitlines():
            if not raw.strip() or "Table 2" in raw or re.search(r"ATP 1-02\.1/", raw):
                continue
            m = head.match(raw) if not raw.startswith(" ") else None
            if m:
                if cur:
                    entries.append(_brev(cur, source_id))
                term, rest = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
                cur = [term.strip(), [rest], i, label]
            elif cur and raw.startswith(" "):
                cur[1].append(raw)
    if cur:
        entries.append(_brev(cur, source_id))
    return [e for e in entries if len(e.definition) > 8]


def _brev(cur: list, sid: str) -> Entry:
    body = _join(cur[1])
    tags = re.findall(r"\[([A-Z/-]+)\]", body)
    clean = re.sub(r"^(\*+\s*)?(\[[A-Z/-]+\]\s*)+", "", body).strip()
    return Entry(term=cur[0], definition=clean, source_id=sid, pdf_page=cur[2] + 1, page_label=cur[3], tags=tags)


def all_doctrine() -> dict[str, list[Entry]]:
    """Every parsed source, in the seed-list priority order (D-028)."""
    return {
        "us-dod-dict-2026-08": dod_dictionary("us-dod-dict-2026-08"),
        "us-atp-3-01-81": atp_glossary("us-atp-3-01-81"),
        "us-jp-3-10": jp_glossary("us-jp-3-10"),
        "us-jp-3-01": jp_glossary("us-jp-3-01"),
        "us-dod-dict-2025-06": dod_dictionary("us-dod-dict-2025-06"),
        "us-dod-dict-2021-11": dod_dictionary("us-dod-dict-2021-11"),
        "us-atp-1-02-1": brevity("us-atp-1-02-1"),
    }
