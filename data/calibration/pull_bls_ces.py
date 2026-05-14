#!/usr/bin/env python3
"""
Extract per-archetype spending distributions from BLS CES PUMD.

Reads:  data/calibration/raw/intrvw{YY}.zip
        data/calibration/raw/doc{YY}.zip      (optional; for UCC ref)
Writes: data/calibration/archetype_profiles.json

Pipeline:
  1. Open intrvw{YY}.zip (no disk unzip — read CSVs from buffer).
  2. Read all FMLI quarterly CSVs, concat. FMLI = household-quarter
     records with demographics + annualized income.
  3. Compute derived fields: expense_to_income, debt_service_ratio.
  4. Read all MTBI quarterly CSVs, concat. MTBI = monthly expenditure
     rows by NEWID + UCC.
  5. Map MTBI.UCC -> Artha category via category_mapping.map_ucc.
  6. For each archetype:
       a. Filter FMLI rows matching the demographic predicate.
       b. Inner-join to MTBI rows on NEWID.
       c. Sum quarterly expenditure per (NEWID, category) -> annualize.
       d. Compute mean + std + percentiles per category across
          households.
       e. Compute monthly_total mean + std for normalization.
  7. Emit JSON with structure:
       {
         "metadata": { "source_year": 2022, "households_total": N, ... },
         "archetypes": {
           "<archetype>": {
             "n_households": int,
             "monthly_total_mean": float,
             "monthly_total_std": float,
             "income_mean_annual": float,
             "categories": {
               "<CATEGORY>": {
                 "share": float,        # fraction of total spend
                 "monthly_mean": float,
                 "monthly_std": float,
                 "p25": float, "p50": float, "p75": float
               },
               ...
             }
           },
           ...
         }
       }

Run:
  py -3 data/calibration/pull_bls_ces.py --year 22
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

try:
    import pandas as pd
    import numpy as np
    import pyreadstat
except ImportError as e:
    print(f"ERROR: missing dep ({e}). Run: pip install pandas numpy pyreadstat",
          file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "data" / "calibration"))

from category_mapping import (  # noqa: E402
    map_ucc, ARTHA_CATEGORIES, UNCALIBRATED_CATEGORIES,
)
from archetype_filters import (  # noqa: E402
    ARCHETYPE_FILTERS, MIN_HOUSEHOLDS_PER_ARCHETYPE,
)

CALIB_DIR = REPO_ROOT / "data" / "calibration"
RAW_DIR = CALIB_DIR / "raw"
OUT_PATH = CALIB_DIR / "archetype_profiles.json"


# ── Helpers ──────────────────────────────────────────────────────

def _normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    """CES PUMD historically uppercase; recent years mixed.
    Normalize to UPPER for deterministic predicate writing."""
    df.columns = [c.upper() for c in df.columns]
    return df


def _ensure_extracted(stem: str) -> Path:
    """Extract {stem}.zip to RAW_DIR/{stem}/ if not already done.
    pyreadstat needs real file paths; can't read .sas7bdat from a buffer.
    `stem` is e.g. 'intrvw24' or 'diary24'."""
    extract_dir = RAW_DIR / stem
    if extract_dir.exists() and any(extract_dir.glob("*.sas7bdat")):
        return extract_dir
    zip_path = RAW_DIR / f"{stem}.zip"
    if not zip_path.exists():
        raise SystemExit(
            f"PUMD zip not found: {zip_path}\n"
            f"Download from https://www.bls.gov/cex/pumd_data.htm "
            f"and place in {RAW_DIR}/"
        )
    print(f"Extracting {zip_path} -> {RAW_DIR} (one-time)...")
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(RAW_DIR)
    return extract_dir


def _read_sas_files(year: int, prefix: str, survey: str = "intrvw") -> pd.DataFrame:
    """Read all SAS files matching `prefix*.sas7bdat` from the
    extracted PUMD directory. `survey` is 'intrvw' or 'diary'."""
    extract_dir = _ensure_extracted(f"{survey}{year:02d}")
    candidates = sorted(extract_dir.glob(f"{prefix}*.sas7bdat"))
    if not candidates:
        raise SystemExit(
            f"No '{prefix}*.sas7bdat' in {extract_dir}.\n"
            f"Files present: {[p.name for p in extract_dir.glob('*.sas7bdat')][:20]}"
        )
    frames = []
    for path in candidates:
        df, _meta = pyreadstat.read_sas7bdat(str(path))
        df = _normalize_columns(df)
        frames.append(df)
        print(f"  read {path.name}: {len(df):,} rows, {len(df.columns)} cols")
    return pd.concat(frames, ignore_index=True)


# Diary 14-day-per-CU diary → monthly multiplier. CES Diary methodology
# captures ~14 days per consumer unit; multiply per-HH sums by 30/14.
DIARY_DAYS_TO_MONTH = 30.0 / 14.0


def _diary_food_means_by_archetype(
    year: int, interview_archetypes: dict
) -> dict:
    """Return {archetype: {food_cat: monthly_mean}} from Diary survey.
    Used to overwrite the Interview-underreported food values."""
    print(f"\nReading Diary FMLD + EXPD (year=20{year:02d}) ...")
    fmld = _read_sas_files(year, "fmld", survey="diary")
    expd = _read_sas_files(year, "expd", survey="diary")
    print(f"  Diary FMLD: {len(fmld):,} HHs, EXPD: {len(expd):,} rows")

    # Derive INCLASS2 from INC_RANK (continuous 0-1) — bin into sextiles
    # matching the Interview INCLASS2 (1-6) frequency distribution
    # (roughly equal-sixths since INC_RANK is uniform by construction).
    fmld["INC_RANK"] = pd.to_numeric(fmld.get("INC_RANK", 0.5), errors="coerce").fillna(0.5)
    fmld["INCLASS2"] = pd.qcut(
        fmld["INC_RANK"], q=6, labels=["1", "2", "3", "4", "5", "6"]
    ).astype(str)

    # Map Diary EXPD UCCs and filter to food
    expd["COST"] = pd.to_numeric(expd["COST"], errors="coerce").fillna(0)
    expd = expd[expd["COST"] >= 0]
    expd["ARTHA_CATEGORY"] = expd["UCC"].apply(map_ucc)
    food_cats = {"GROCERIES", "FOOD_AND_DRINK"}
    expd_food = expd[expd["ARTHA_CATEGORY"].isin(food_cats)]
    print(f"  Diary food rows: {len(expd_food):,} (of {len(expd):,})")

    # Per-household food spend for the diary period, scaled to monthly
    per_hh = (
        expd_food.groupby(["NEWID", "ARTHA_CATEGORY"])["COST"].sum()
        * DIARY_DAYS_TO_MONTH
    ).reset_index()
    per_hh = per_hh.rename(columns={"COST": "MONTHLY_FOOD"})

    # Join household-level demographics
    hh_food = per_hh.merge(
        fmld[["NEWID", "INCLASS2", "AGE_REF", "FAM_SIZE",
              "NO_EARNR", "MARITAL1", "EDUC_REF"]],
        on="NEWID", how="left"
    )

    # Compute Diary food means per archetype where the predicate
    # depends only on fields available in Diary FMLD. For the rest,
    # fall back to the overall Diary mean per category.
    overall_means = hh_food.groupby("ARTHA_CATEGORY")["MONTHLY_FOOD"].mean().to_dict()
    print(f"  Diary overall food means: {overall_means}")

    diary_supportable = {
        "high_earner", "recent_grad", "retired_fixed", "dual_income"
    }

    out = {}
    for arch in interview_archetypes:
        if arch not in diary_supportable:
            out[arch] = dict(overall_means)
            continue

        from archetype_filters import ARCHETYPE_FILTERS
        pred = ARCHETYPE_FILTERS[arch]
        mask = hh_food.apply(
            lambda r: pred({**r, "expense_to_income": 0.0}), axis=1
        )
        sub = hh_food[mask]
        if len(sub) < 30:
            print(f"  [diary] {arch}: only {len(sub)} HHs — falling back to overall")
            out[arch] = dict(overall_means)
        else:
            out[arch] = sub.groupby("ARTHA_CATEGORY")["MONTHLY_FOOD"].mean().to_dict()

    return out


def _add_derived_fields(fmli: pd.DataFrame) -> pd.DataFrame:
    """Compute expense_to_income. (debt_service_ratio dropped in
    2024 schema — BSTREDC/BSTRINC no longer in FMLI; high_debt
    predicate uses OTHLOAN proxy instead.)"""
    fmli = fmli.copy()
    totexp_annual = pd.to_numeric(
        fmli.get("TOTEXPCQ", 0), errors="coerce"
    ).fillna(0) * 4.0
    income = pd.to_numeric(
        fmli.get("FINCBTAX", 0), errors="coerce"
    ).fillna(0)
    fmli["EXPENSE_TO_INCOME"] = np.where(
        income > 0, totexp_annual / income, 0.0
    )
    return fmli


def _archetype_filter_to_mask(
    fmli: pd.DataFrame, predicate
) -> pd.Series:
    """Apply a predicate row-by-row. Slow but predicates are simple
    and FMLI is ~25k rows; vectorizing isn't worth the complexity."""
    return fmli.apply(
        lambda r: predicate({**r, "expense_to_income": r["EXPENSE_TO_INCOME"]}),
        axis=1,
    )


# ── Main extraction ──────────────────────────────────────────────

def extract(year: int) -> dict:
    print(f"Reading FMLI (year=20{year:02d}) ...")
    fmli = _read_sas_files(year, "fmli")
    print(f"  FMLI total: {len(fmli):,} rows, {len(fmli.columns)} cols")
    # Drop INCLASS2 == 7 (BLS "incomplete income reporter" bucket).
    # 1500+ of these have FINCBTAX=0 and would distort all derived
    # ratios. Real income classes are the 1-6 sextiles.
    if "INCLASS2" in fmli.columns:
        before = len(fmli)
        # SAS PUMD encodes INCLASS2 as string ('1'..'7'); compare as str.
        fmli = fmli[fmli["INCLASS2"].astype(str) != "7"].reset_index(drop=True)
        print(f"  Dropped {before - len(fmli):,} INCLASS2=7 (incomplete reporter) rows")
    fmli = _add_derived_fields(fmli)

    print(f"Reading MTBI (year=20{year:02d}) ...")
    mtbi = _read_sas_files(year, "mtbi")
    print(f"  MTBI total: {len(mtbi):,} rows, {len(mtbi.columns)} cols")
    # Drop negative COST rows (BLS refund/correction entries; ~few %).
    # Keep zeros — those are legitimate "no spending in this category
    # this month" signal that should pull means down honestly.
    mtbi["COST"] = pd.to_numeric(mtbi["COST"], errors="coerce")
    before = len(mtbi)
    mtbi = mtbi[mtbi["COST"] >= 0].reset_index(drop=True)
    print(f"  Dropped {before - len(mtbi):,} negative-COST rows")

    # Map UCC -> Artha category. UCC column is 'UCC' (or 'ucc');
    # value is the 6-digit code.
    mtbi["ARTHA_CATEGORY"] = mtbi["UCC"].apply(map_ucc)
    unmapped = mtbi[mtbi["ARTHA_CATEGORY"].isna()]
    if len(unmapped):
        top_unmapped = unmapped["UCC"].value_counts().head(20)
        print(f"  WARNING: {len(unmapped):,} MTBI rows have unmapped "
              f"UCCs. Top 20:\n{top_unmapped.to_string()}")
    mtbi = mtbi.dropna(subset=["ARTHA_CATEGORY"])

    # COST is the expenditure value column in MTBI; numeric, USD.
    mtbi["COST"] = pd.to_numeric(mtbi["COST"], errors="coerce").fillna(0)

    # Aggregate per (NEWID, category): sum cost across all months/UCCs.
    # MTBI's COST is already monthly per UCC, so summing gives total
    # quarterly expenditure per category per household.
    per_household = (
        mtbi.groupby(["NEWID", "ARTHA_CATEGORY"])["COST"].sum().reset_index()
    )

    # Pivot to NEWID-by-category for easy demographic joins.
    spend_wide = per_household.pivot(
        index="NEWID", columns="ARTHA_CATEGORY", values="COST"
    ).fillna(0)

    profiles = {}
    for archetype, predicate in ARCHETYPE_FILTERS.items():
        mask = _archetype_filter_to_mask(fmli, predicate)
        matched = fmli[mask]
        n = len(matched)
        if n < MIN_HOUSEHOLDS_PER_ARCHETYPE:
            print(f"  WARN: {archetype}: only {n} households "
                  f"(below threshold {MIN_HOUSEHOLDS_PER_ARCHETYPE})")
        # Inner-join to spend_wide via NEWID
        newids = set(matched["NEWID"])
        sub = spend_wide.loc[spend_wide.index.isin(newids)]
        if sub.empty:
            print(f"  SKIP: {archetype}: zero spend rows")
            continue

        # Annualize quarterly spend then convert to monthly mean.
        # CES MTBI cost is already monthly-per-UCC sum across the
        # quarter — divide by 3 to get monthly average per household.
        monthly = sub / 3.0
        monthly_total = monthly.sum(axis=1)

        cat_stats = {}
        for cat in monthly.columns:
            series = monthly[cat]
            cat_stats[cat] = {
                "share": float(series.sum() / monthly_total.sum())
                          if monthly_total.sum() > 0 else 0.0,
                "monthly_mean": float(series.mean()),
                "monthly_std":  float(series.std()),
                "p25": float(series.quantile(0.25)),
                "p50": float(series.quantile(0.50)),
                "p75": float(series.quantile(0.75)),
            }

        income_mean = float(matched["FINCBTAX"].mean())

        profiles[archetype] = {
            "n_households": n,
            "monthly_total_mean": float(monthly_total.mean()),
            "monthly_total_std":  float(monthly_total.std()),
            "income_mean_annual": income_mean,
            "categories": cat_stats,
        }
        print(f"  {archetype}: n={n}, monthly_total_mean=${monthly_total.mean():.0f}")

    # Overwrite food category values with Diary-derived means.
    # BLS Interview survey heavily underreports food (food is captured
    # by the Diary survey instead). Apply Diary calibration overlay.
    diary_food = _diary_food_means_by_archetype(year, profiles)
    food_cats = {"GROCERIES", "FOOD_AND_DRINK"}
    for arch_name, prof in profiles.items():
        for cat in food_cats:
            if cat not in prof["categories"]:
                continue
            diary_mean = diary_food.get(arch_name, {}).get(cat)
            if diary_mean is None or diary_mean <= 0:
                continue
            iv_mean = prof["categories"][cat]["monthly_mean"]
            if iv_mean <= 0:
                continue
            scale = diary_mean / iv_mean
            for stat in ("monthly_mean", "monthly_std", "p25", "p50", "p75"):
                prof["categories"][cat][stat] *= scale
            prof["categories"][cat]["calibration_source"] = "diary_2024"
        # Renormalize shares since food values changed
        total = sum(c["monthly_mean"] for c in prof["categories"].values())
        if total > 0:
            for c in prof["categories"].values():
                c["share"] = c["monthly_mean"] / total

    return {
        "metadata": {
            "source": "BLS Consumer Expenditure Survey PUMD",
            "source_year": 2000 + year,
            "households_total": int(fmli["NEWID"].nunique()),
            "categories_calibrated": list(spend_wide.columns),
            "categories_uncalibrated": list(UNCALIBRATED_CATEGORIES),
            "food_calibration_source": "BLS Diary survey (FMLD + EXPD), "
                                        "scaled from 14-day-per-CU diary period to monthly",
        },
        "archetypes": profiles,
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--year", type=int, default=22,
                   help="2-digit year, e.g. 22 for intrvw22.zip")
    p.add_argument("--out", type=Path, default=OUT_PATH)
    args = p.parse_args()

    result = extract(args.year)
    args.out.write_text(json.dumps(result, indent=2))
    print(f"\nWrote {args.out} ({args.out.stat().st_size:,} bytes)")
    print(f"  Archetypes profiled: {len(result['archetypes'])} / "
          f"{len(ARCHETYPE_FILTERS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
