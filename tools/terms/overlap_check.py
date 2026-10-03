#!/usr/bin/env python3
"""
overlap_check.py - verbatim-overlap gate for Mokuhyo term content.

Flags generated text that shares a long run of words (or characters, for
ja/zh/ko) with any source whose SOURCES.json row has verbatim_ok == false.
Runs that also appear in a public-domain source (verbatim_ok == true, e.g. the
DoD Dictionary) are reported as "pd-ok" and do not fail the gate, because NATO
and allied glossaries often repeat US definitions word for word.

Usage
  python tools/terms/overlap_check.py index [--only ID,ID] [--force]
  python tools/terms/overlap_check.py check FILE [FILE ...] [--fields definition,example]
                                            [--word-n 8] [--char-n 20] [--json REPORT.json]
                                            [--allow tools/terms/overlap_allow.txt]

FILE may be .csv, .jsonl or .json (array of objects). Each item needs `lang`
and an id column (`id`, `seed_id`, or row number is used). Text is read from
the --fields columns that exist.

Exit codes: 0 = clean, 1 = overlap found (gate fails), 2 = usage / setup error.

The text cache (tools/sources/.cache/) holds extracted source text for
comparison only. Keep it out of git and out of any shipped build.
"""
import argparse
import csv
import hashlib
import json
import os
import shutil
import subprocess
import sys
import unicodedata
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SOURCES_DIR = os.path.join(REPO, 'tools', 'sources')
SOURCES_JSON = os.path.join(SOURCES_DIR, 'SOURCES.json')
CACHE = os.path.join(SOURCES_DIR, '.cache', 'text')

CHAR_LANGS = {'ja', 'zh', 'zh-Hans', 'zh-Hant', 'ko'}   # compared as character streams
DEFAULT_FIELDS = ['definition', 'definition_en', 'example', 'text', 'prompt', 'answer', 'explanation']


# ---------------------------------------------------------------- helpers
def die(msg):
    print(f'overlap_check: {msg}', file=sys.stderr)
    sys.exit(2)


def load_sources():
    if not os.path.exists(SOURCES_JSON):
        die(f'missing {SOURCES_JSON}')
    with open(SOURCES_JSON, encoding='utf-8') as f:
        return json.load(f)['sources']


def lang_family(lang):
    return 'zh' if lang and lang.startswith('zh') else lang


def is_char_lang(lang):
    return lang in CHAR_LANGS or lang_family(lang) in CHAR_LANGS


def normalize(text):
    """NFKC, lowercase, every non letter/number/mark becomes a space."""
    text = unicodedata.normalize('NFKC', text).lower()
    out = []
    for ch in text:
        cat = unicodedata.category(ch)
        out.append(ch if cat[0] in 'LNM' else ' ')
    return ' '.join(''.join(out).split())


def units(norm, char_mode):
    """Comparison units: characters (spaces dropped) or words."""
    return list(norm.replace(' ', '')) if char_mode else norm.split()


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def extract_pdf(path):
    if shutil.which('pdftotext'):
        r = subprocess.run(['pdftotext', '-enc', 'UTF-8', path, '-'], capture_output=True, check=False)
        if r.returncode == 0:
            return r.stdout.decode('utf-8', 'replace')
    try:
        from pypdf import PdfReader
    except ImportError:
        die('need poppler pdftotext or `pip install pypdf` to extract text')
    return '\n'.join((p.extract_text() or '') for p in PdfReader(path).pages)


def cache_paths(sid):
    return os.path.join(CACHE, sid + '.txt'), os.path.join(CACHE, sid + '.meta.json')


def indexable(rows):
    # 'limited' = releasability-controlled doctrine: never extracted, never indexed
    # superseded editions stay indexed: a public-domain match in an older DoD Dictionary is still public domain
    return [r for r in rows if r.get('status') in ('acquired', 'superseded') and r.get('path')
            and r.get('distribution') != 'limited'
            and r.get('verbatim_ok') in (True, False)]


# ---------------------------------------------------------------- index
def cmd_index(args):
    rows = indexable(load_sources())
    if args.only:
        want = set(args.only.split(','))
        rows = [r for r in rows if r['id'] in want]
    os.makedirs(CACHE, exist_ok=True)
    done = skipped = 0
    for r in rows:
        src = os.path.join(SOURCES_DIR, r['path'])
        txt, meta = cache_paths(r['id'])
        if not os.path.exists(src):
            print(f'  MISSING  {r["id"]}: {r["path"]}')
            continue
        if not args.force and os.path.exists(txt) and os.path.exists(meta):
            with open(meta, encoding='utf-8') as f:
                if json.load(f).get('sha256') == r.get('sha256'):
                    skipped += 1
                    continue
        if r.get('sha256') and sha256(src) != r['sha256']:
            print(f'  WARNING  {r["id"]}: file hash differs from SOURCES.json')
        raw = extract_pdf(src)
        norm = normalize(raw)
        with open(txt, 'w', encoding='utf-8') as f:
            f.write(norm)
        with open(meta, 'w', encoding='utf-8') as f:
            json.dump({'id': r['id'], 'sha256': r.get('sha256'), 'chars': len(norm)}, f)
        done += 1
        print(f'  indexed  {r["id"]}  ({len(norm):,} chars)')
    print(f'index: {done} extracted, {skipped} up to date')


# ---------------------------------------------------------------- check
def read_items(path, fields):
    ext = os.path.splitext(path)[1].lower()
    if ext == '.csv':
        with open(path, encoding='utf-8-sig', newline='') as f:
            recs = list(csv.DictReader(f))
    elif ext == '.jsonl':
        with open(path, encoding='utf-8') as f:
            recs = [json.loads(l) for l in f if l.strip()]
    elif ext == '.json':
        with open(path, encoding='utf-8') as f:
            recs = json.load(f)
        if isinstance(recs, dict):
            recs = recs.get('items', [])
    else:
        die(f'unsupported file type: {path}')
    items = []
    for i, rec in enumerate(recs, 1):
        lang = (rec.get('lang') or '').strip()
        rid = rec.get('id') or rec.get('seed_id') or f'row{i}'
        if rec.get('seed_id') and lang:
            rid = f'{rec["seed_id"]}/{lang}'
        for fld in fields:
            val = rec.get(fld)
            if isinstance(val, str) and val.strip():
                items.append({'file': os.path.basename(path), 'id': str(rid),
                              'lang': lang or 'en', 'field': fld, 'text': val})
    return items


def longest_runs(positions, n):
    """positions: sorted shingle start indexes -> list of (start, end_exclusive_unit)."""
    runs, start, prev = [], None, None
    for p in positions:
        if start is None:
            start = prev = p
        elif p == prev + 1:
            prev = p
        else:
            runs.append((start, prev + n))
            start = prev = p
    if start is not None:
        runs.append((start, prev + n))
    return runs


def cmd_check(args):
    fields = args.fields.split(',') if args.fields else DEFAULT_FIELDS
    items = []
    for p in args.files:
        items.extend(read_items(p, fields))
    if not items:
        print('check: no text found in the given fields')
        if args.json:  # callers read the report; an empty check still writes one
            with open(args.json, 'w', encoding='utf-8') as f:
                json.dump({'findings': [], 'failed': False, 'checked': 0}, f)
        return 0

    rows = indexable(load_sources())
    restricted = [r for r in rows if r['verbatim_ok'] is False]
    public = [r for r in rows if r['verbatim_ok'] is True]
    missing = [r['id'] for r in restricted + public if not os.path.exists(cache_paths(r['id'])[0])]
    if missing:
        die('text cache missing for: ' + ', '.join(missing) + '\n  run: python tools/terms/overlap_check.py index')

    allow = []
    if args.allow and os.path.exists(args.allow):
        with open(args.allow, encoding='utf-8') as f:
            allow = [normalize(l) for l in f if l.strip() and not l.startswith('#')]

    # Shingle every item; map shingle -> [(item_index, position)]
    shingle_map = {'word': defaultdict(list), 'char': defaultdict(list)}
    item_units = []
    for idx, it in enumerate(items):
        cm = is_char_lang(it['lang'])
        n = args.char_n if cm else args.word_n
        u = units(normalize(it['text']), cm)
        item_units.append((u, cm, n))
        m = shingle_map['char' if cm else 'word']
        for pos in range(len(u) - n + 1):
            m[tuple(u[pos:pos + n])].append((idx, pos))

    mode_langs = {mode: {lang_family(items[i]['lang']) for lst in m.values() for i, _ in lst}
                  for mode, m in shingle_map.items()}

    def scan(source_rows):
        """Return {(item_idx, source_id): set(positions)} for sources sharing the item's language."""
        hits = defaultdict(set)
        for r in source_rows:
            fams = {lang_family(l) for l in r.get('lang', [])}
            for mode in ('word', 'char'):
                m = shingle_map[mode]
                if not m:
                    continue
                n = args.char_n if mode == 'char' else args.word_n
                # only scan if some item in this mode shares a language family with the source
                if not (mode_langs[mode] & fams):
                    continue
                with open(cache_paths(r['id'])[0], encoding='utf-8') as f:
                    su = units(f.read(), mode == 'char')
                for p in range(len(su) - n + 1):
                    lst = m.get(tuple(su[p:p + n]))
                    if lst:
                        for idx, pos in lst:
                            if lang_family(items[idx]['lang']) in fams:
                                hits[(idx, r['id'])].add(pos)
        return hits

    restricted_hits = scan(restricted)

    # Public-domain text, joined, for safe-harbour substring checks
    pd_text = {}
    if restricted_hits:
        for r in public:
            with open(cache_paths(r['id'])[0], encoding='utf-8') as f:
                t = f.read()
            pd_text[r['id']] = (t, t.replace(' ', ''))

    findings, failed = [], False
    for (idx, sid), poss in sorted(restricted_hits.items()):
        it = items[idx]
        u, cm, n = item_units[idx]
        for a, b in longest_runs(sorted(poss), n):
            seg = u[a:b]
            run = ''.join(seg) if cm else ' '.join(seg)
            if any(run in al for al in allow):
                verdict, pd_src = 'allowed', None
            else:
                pd_src = next((pid for pid, (spaced, packed) in pd_text.items()
                               if run in (packed if cm else spaced)), None)
                verdict = 'pd-ok' if pd_src else 'FAIL'
            if verdict == 'FAIL':
                failed = True
            findings.append({'verdict': verdict, 'file': it['file'], 'item': it['id'], 'lang': it['lang'],
                             'field': it['field'], 'source': sid, 'public_domain_match': pd_src,
                             'run_units': b - a, 'unit': 'chars' if cm else 'words', 'run': run})

    for fd in findings:
        tag = {'FAIL': 'FAIL ', 'pd-ok': 'ok-PD', 'allowed': 'allow'}[fd['verdict']]
        extra = f' (also in {fd["public_domain_match"]})' if fd['public_domain_match'] else ''
        print(f'{tag} {fd["file"]}:{fd["item"]} [{fd["field"]}] <-> {fd["source"]}: '
              f'{fd["run_units"]} {fd["unit"]}{extra}\n       "{fd["run"][:160]}"')
    n_fail = sum(f['verdict'] == 'FAIL' for f in findings)
    print(f'check: {len(items)} text fields, {len(findings)} overlaps, {n_fail} failing')
    if args.json:
        with open(args.json, 'w', encoding='utf-8') as f:
            json.dump({'items_checked': len(items), 'failing': n_fail, 'findings': findings},
                      f, ensure_ascii=False, indent=2)
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    a = sub.add_parser('index', help='extract and cache source text')
    a.add_argument('--only', help='comma-separated source ids')
    a.add_argument('--force', action='store_true')
    c = sub.add_parser('check', help='check generated content files')
    c.add_argument('files', nargs='+')
    c.add_argument('--fields', help='comma-separated text columns to check')
    c.add_argument('--word-n', type=int, default=8, help='shared consecutive words that count as copying (default 8)')
    c.add_argument('--char-n', type=int, default=20, help='shared consecutive characters for ja/zh/ko (default 20)')
    c.add_argument('--allow', default=os.path.join(HERE, 'overlap_allow.txt'))
    c.add_argument('--json', help='write a JSON report here')
    args = ap.parse_args()
    if args.cmd == 'index':
        cmd_index(args)
        return 0
    return cmd_check(args)


if __name__ == '__main__':
    sys.exit(main())
