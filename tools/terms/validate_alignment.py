#!/usr/bin/env python3
"""
validate_alignment.py - schema and provenance gate for tools/terms/term_alignment.csv

One row = one seed term in one target language.

Columns (header required, order free):
  seed_id           id from seed_terms.csv (e.g. cuas-001)
  lang              ja ko de fr es pt-BR ru ar fa id zh-Hans zh-Hant
  term              the target-language term
  term_source_id    SOURCES.json id the term was confirmed in; empty = model-proposed
  term_source_page  page (or section) in that source; required when term_source_id is set
  definition        target-language definition, drafted from the seed's US definition_en
  status            draft | checked | approved
  badge             '' | unconfirmed-term | unreviewed
  approvedBy        reviewer name; required when status = approved
  notes             free text

Rules
  - (seed_id, lang) unique; seed_id must exist in seed_terms.csv (if found)
  - term_source_id must exist in SOURCES.json, be status acquired or manual,
    have alignment_ok true, and list the row's language (zh-Hans/zh-Hant share 'zh')
  - empty term_source_id  -> badge must be 'unconfirmed-term' and status cannot be approved
    unless approvedBy is set AND notes explain the reviewer accepted the proposed term
  - status approved       -> approvedBy required, badge must be '' (reviewer cleared it)
  - status draft/checked  -> badge required (unreviewed or unconfirmed-term)
  - definition required unless status is draft

Usage: python tools/terms/validate_alignment.py [tools/terms/term_alignment.csv] [--seeds PATH]
Exit 0 = valid, 1 = errors, 2 = setup error.
"""
import argparse, csv, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
LANGS = {'ja', 'ko', 'de', 'fr', 'es', 'pt-BR', 'ru', 'ar', 'fa', 'id', 'zh-Hans', 'zh-Hant'}
STATUSES = {'draft', 'checked', 'approved'}
BADGES = {'', 'unconfirmed-term', 'unreviewed'}
REQUIRED = ['seed_id', 'lang', 'term', 'term_source_id', 'term_source_page',
            'definition', 'status', 'badge', 'approvedBy', 'notes']


def fam(lang):
    return 'zh' if lang.startswith('zh') else lang


def find_seeds(explicit):
    for p in [explicit, os.path.join(REPO, 'seed_terms.csv'),
              os.path.join(REPO, 'tools', 'terms', 'seed_terms.csv'),
              os.path.join(REPO, 'tools', 'seed_terms.csv')]:
        if p and os.path.exists(p):
            return p
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('csv', nargs='?', default=os.path.join(HERE, 'term_alignment.csv'))
    ap.add_argument('--seeds')
    ap.add_argument('--sources', default=os.path.join(REPO, 'tools', 'sources', 'SOURCES.json'))
    a = ap.parse_args()

    if not os.path.exists(a.csv):
        print(f'validate_alignment: {a.csv} not found', file=sys.stderr); return 2
    if not os.path.exists(a.sources):
        print(f'validate_alignment: {a.sources} not found', file=sys.stderr); return 2
    with open(a.sources, encoding='utf-8') as f:
        sources = {r['id']: r for r in json.load(f)['sources']}

    seed_ids = None
    sp = find_seeds(a.seeds)
    if sp:
        with open(sp, encoding='utf-8-sig', newline='') as f:
            seed_ids = {r['id'] for r in csv.DictReader(f)}
    else:
        print('note: seed_terms.csv not found; skipping seed_id existence check')

    errors, seen = [], set()
    with open(a.csv, encoding='utf-8-sig', newline='') as f:
        rd = csv.DictReader(f)
        missing = [c for c in REQUIRED if c not in (rd.fieldnames or [])]
        if missing:
            print('missing columns: ' + ', '.join(missing)); return 1
        for n, r in enumerate(rd, 2):
            r = {k: (v or '').strip() for k, v in r.items()}
            where = f'line {n} ({r["seed_id"]}/{r["lang"]})'
            def err(msg): errors.append(f'{where}: {msg}')

            key = (r['seed_id'], r['lang'])
            if key in seen: err('duplicate seed_id + lang')
            seen.add(key)
            if not r['seed_id']: err('seed_id empty')
            elif seed_ids is not None and r['seed_id'] not in seed_ids: err('seed_id not in seed_terms.csv')
            if r['lang'] not in LANGS: err(f'unknown lang "{r["lang"]}"')
            if not r['term']: err('term empty')
            if r['status'] not in STATUSES: err(f'status must be one of {sorted(STATUSES)}')
            if r['badge'] not in BADGES: err(f'badge must be one of {sorted(b for b in BADGES if b)} or empty')

            sid = r['term_source_id']
            if sid:
                s = sources.get(sid)
                if not s: err(f'term_source_id "{sid}" not in SOURCES.json')
                else:
                    if s.get('alignment_ok') is not True: err(f'{sid} is not alignment_ok')
                    if s.get('status') not in ('acquired', 'manual', 'manual-cac'): err(f'{sid} status is {s.get("status")}')
                    if fam(r['lang']) not in {fam(l) for l in s.get('lang', [])}: err(f'{sid} does not cover {r["lang"]}')
                if not r['term_source_page']: err('term_source_page required when term_source_id is set')
            else:
                if r['status'] == 'approved':
                    if not (r['approvedBy'] and r['notes']):
                        err('model-proposed term approved without approvedBy + notes explaining acceptance')
                elif r['badge'] != 'unconfirmed-term':
                    err('no term_source_id: badge must be unconfirmed-term')

            if r['status'] == 'approved':
                if not r['approvedBy']: err('approved without approvedBy')
                if r['badge']: err('approved rows must have an empty badge')
            elif r['status'] in ('draft', 'checked') and not r['badge']:
                err('unapproved rows need a badge (unreviewed or unconfirmed-term)')
            if r['status'] != 'draft' and not r['definition']: err('definition empty')

    for e in errors:
        print('ERROR ' + e)
    print(f'validate_alignment: {len(seen)} rows, {len(errors)} errors')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
