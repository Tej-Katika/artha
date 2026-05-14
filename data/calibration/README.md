# BLS CES Calibration

Calibrates the Artha synthetic banking generator
(`generate_artha_data_v2.py`) against the U.S. Bureau of Labor
Statistics Consumer Expenditure Survey (CES) Public Use Microdata
(PUMD), so paper-side framing moves from "100% synthetic" to
"synthetic calibrated against BLS CES distributions".

## Pipeline

```
raw/intrvw22.zip            ← user-downloaded (BLS WAF blocks bots)
raw/doc22.zip               ← user-downloaded (UCC dictionary)
        │
        ▼
pull_bls_ces.py
  ├── unzip + read FMLI*.csv (family characteristics)
  ├── unzip + read MTBI*.csv (monthly expenditures by UCC)
  ├── join on NEWID
  ├── apply archetype_filters.py per archetype
  ├── aggregate via category_mapping.py (UCC → your-15-categories)
  └── compute mean + std per category per archetype
        │
        ▼
archetype_profiles.json     ← consumed by generate_artha_data_v2.py
```

## Files

| File | Purpose | Status |
|---|---|---|
| `raw/intrvw22.zip` | BLS PUMD interview survey, 2022 | user-downloaded |
| `raw/doc22.zip` | BLS PUMD documentation incl. UCC dict | user-downloaded |
| `category_mapping.py` | UCC → Artha category mapper | written 2026-05-10 |
| `archetype_filters.py` | Archetype → CES demographic predicate | written 2026-05-10 |
| `pull_bls_ces.py` | Main extractor (FMLI + MTBI → JSON) | pending data |
| `archetype_profiles.json` | Per-archetype distribution artifact | pending extractor |

## Why CES PUMD over CES summary tables

PUMD is household-level microdata. Lets us derive empirical
distributions (mean + std + percentiles) per archetype, not just
group means. Summary tables would only give "average household
spending on Food = $X" — no variance — which would make the
synthetic generator deterministic per archetype and lose the
realism gain.

## Caveats (fold into paper §6 Limitations)

- US households only. Calibration does not extend to non-US
  banking behavior.
- 2022 reference year. Pre-pandemic behavior change persistence
  (remote work, food-away-from-home recovery) is partially baked in.
- No merchant-name data in CES. Synthetic merchant pools remain
  hardcoded per archetype. Honest framing: *"transaction
  distributions are calibrated; merchant identities remain
  synthetic for archetype consistency"*.
- No transaction-level timestamps in CES; only monthly expenditures
  by UCC. Monthly volume is calibrated; intra-month timing remains
  synthetic.
