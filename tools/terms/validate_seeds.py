#!/usr/bin/env python3
"""validate_seeds.py - gate for tools/terms/seed_terms.csv (BRIEF_PHASE8 §B.2.1, C-01a).

Hard rules (exit 1 on any): required columns; unique id and term; known domain; priority 1-3; definition present;
definition_source doctrine|original; doctrine rows cite a verbatim_ok, non-limited SOURCES.json id with a page, and
their definition_en appears verbatim in that source (compared on the overlap text cache: case, punctuation and spacing
ignored); original rows say source_doc = authored; approvedBy filled; at least 250 rows; every priority-1 starter term
of seed_catalog.py present.

Reported: the share of rows with a doctrinal definition. --min-doctrinal 0.9 turns the C-01a target into a failure.

Usage: python tools/terms/validate_seeds.py [seed_terms.csv] [--min-doctrinal F]
"""
import argparse
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verbatim
from seed_catalog import CATALOG

REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SOURCES = os.path.join(REPO, 'tools', 'sources', 'SOURCES.json')
DOMAINS = {'cuas', 'base-defense', 'airspace', 'ew', 'roe', 'c2', 'logistics', 'medical', 'hadr', 'brevity'}
COLUMNS = ['id', 'term_en', 'acronym', 'domain', 'priority', 'definition_en', 'definition_source', 'source_id', 'source_doc',
           'source_page', 'notes', 'approvedBy']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('csv', nargs='?', default=os.path.join(HERE, 'seed_terms.csv'))
    ap.add_argument('--min-doctrinal', type=float, default=0.0)
    a = ap.parse_args()
    with open(SOURCES, encoding='utf-8') as f:
        sources = {r['id']: r for r in json.load(f)['sources']}
    with open(a.csv, encoding='utf-8-sig', newline='') as f:
        rd = csv.DictReader(f)
        missing = [c for c in COLUMNS if c not in (rd.fieldnames or [])]
        rows = list(rd)
    errors = []
    if missing:
        errors.append('missing columns: ' + ', '.join(missing))
    ids, terms = set(), set()
    for n, r in enumerate(rows, 2):
        r = {k: (v or '').strip() for k, v in r.items()}
        where = f'line {n} ({r.get("id")})'
        if r['id'] in ids:
            errors.append(f'{where}: duplicate id')
        ids.add(r['id'])
        if r['term_en'].lower() in terms:
            errors.append(f'{where}: duplicate term {r["term_en"]}')
        terms.add(r['term_en'].lower())
        if r['domain'] not in DOMAINS:
            errors.append(f'{where}: unknown domain {r["domain"]}')
        if r['priority'] not in ('1', '2', '3'):
            errors.append(f'{where}: priority must be 1, 2 or 3')
        if not r['definition_en']:
            errors.append(f'{where}: empty definition')
        if not r['approvedBy']:
            errors.append(f'{where}: approvedBy empty')
        if r['definition_source'] == 'doctrine':
            s = sources.get(r['source_id'])
            if not s or s.get('verbatim_ok') is not True or s.get('distribution') == 'limited':
                errors.append(f'{where}: source_id {r["source_id"]!r} is not a verbatim_ok public source')
                continue
            if not r['source_doc'] or not r['source_page']:
                errors.append(f'{where}: doctrine rows need source_doc and source_page')
            if verbatim.verbatim_in(r['definition_en'], r['source_id']) is False:
                errors.append(f'{where}: definition_en is not verbatim in {r["source_id"]}')
        elif r['definition_source'] == 'original':
            if r['source_doc'] != 'authored':
                errors.append(f'{where}: original definitions need source_doc = authored')
        else:
            errors.append(f'{where}: definition_source must be doctrine or original')
    if len(rows) < 250:
        errors.append(f'only {len(rows)} rows (need at least 250)')
    starters = {t.lower() for t, _d, p, *_ in CATALOG if p == 1}
    absent = sorted(starters - terms - {t.lower().split(' (')[0] for t in terms})
    present = {t.lower() for t in terms}
    absent = [t for t in absent if not any(x.startswith(t + ' (') for x in present)]
    if absent:
        errors.append('priority-1 starter terms missing: ' + ', '.join(absent))
    doctrinal = sum(1 for r in rows if (r.get('definition_source') or '').strip() == 'doctrine')
    share = doctrinal / len(rows) if rows else 0
    for e in errors:
        print('ERROR ' + e)
    print(f'validate_seeds: {len(rows)} rows, {doctrinal} doctrinal ({share:.1%}), {len(errors)} errors')
    if share < a.min_doctrinal:
        print(f'validate_seeds: doctrinal share {share:.1%} is below {a.min_doctrinal:.0%}')
        return 1
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
