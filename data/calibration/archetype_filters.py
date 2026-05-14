"""
Archetype -> CES demographic filter predicates.

Each of the 10 Artha banking archetypes maps to a filter function
over an FMLI (Family Characteristics) row. Households passing the
filter are aggregated to derive that archetype's empirical spending
distribution.

FMLI column reference (BLS CES PUMD 2024 — schema differs from
pre-2023 releases):
  AGE_REF    int     Reference person's age
  INCLASS2   int     Income class 1-9 (1=lowest, 9=highest decile)
                     [renamed from INCLASS in pre-2023 releases]
  INC_RANK   float   Income rank, 0-1 continuous (alternative)
  FAM_SIZE   int     Family size
  NO_EARNR   int     Number of earners in household
  MARITAL1   int     Marital status of ref person (1=married,
                       2=widowed, 3=divorced, 4=separated, 5=never)
  BLS_URBN   int     Urban (1) or rural (2)
  REGION     int     Region (1=NE, 2=MW, 3=S, 4=W)
  EDUC_REF   int     Education level (10=no schooling, ..., 17=PhD)
  FINCBTAX   float   Family income before taxes (annual)
  TOTEXPCQ   float   Total expenditures, current quarter
  BUSCREEN   int     Had business income? 1=yes, 2=no
                     [replaces BUS_INCM in pre-2023; amount no longer
                     in FMLI directly, only the boolean]
  FSALARYX   float   Annual salary income
  PRINEARN   int     Principal earner present
  EARNCOMP   int     Earnings composition (1=adult, 2=ref+spouse,
                     3=ref+other, 4=ref alone, 5=other only)

Notes on schema drift since pre-2023 releases:
- BSTREDC / BSTRINC (credit card / installment debt amounts) no
  longer in FMLI directly. Multiple CREDIT*/OTHLOAN variants exist
  but the documentation needed to pick the right one is in doc24.zip
  (which BLS no longer publishes for 2024). Dropping debt_service_
  ratio for now; high_debt archetype uses income + OTHLOAN proxy.
- BUS_INCM (business income amount) became BUSCREEN (boolean).
  Freelancer/gig/small_biz_owner now distinguished by FSALARYX tier
  (annual salary) + BUSCREEN flag. Coarser than amount-based
  segmentation but defensible.
"""

from __future__ import annotations
from typing import Callable, Mapping, Any


FMLIRow = Mapping[str, Any]


def _i(row: FMLIRow, col: str, default: int = 0) -> int:
    v = row.get(col)
    if v is None or v == "":
        return default
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def _f(row: FMLIRow, col: str, default: float = 0.0) -> float:
    v = row.get(col)
    if v is None or v == "":
        return default
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def _has_business(row: FMLIRow) -> bool:
    """BUSCREEN: 1=yes, 2=no. NaN/missing treated as no."""
    return _i(row, "BUSCREEN") == 1


# ── Per-archetype filter predicates ───────────────────────────────

def is_high_earner(row: FMLIRow) -> bool:
    """Top income sextile (CES 2024 INCLASS2 1-6 scale; class 7 is
    'incomplete income reporter' and is filtered globally upstream),
    working age."""
    return _i(row, "INCLASS2") == 6 and 35 <= _i(row, "AGE_REF") <= 60


def is_paycheck_to_paycheck(row: FMLIRow) -> bool:
    """Mid-low income, large household, urban, high spend-to-income.
    Derived field 'expense_to_income' precomputed in pull_bls_ces.py."""
    inclass = _i(row, "INCLASS2")
    fam = _i(row, "FAM_SIZE")
    urban = _i(row, "BLS_URBN") == 1
    eti = _f(row, "expense_to_income", 0.0)
    return (3 <= inclass <= 5) and fam >= 2 and urban and eti >= 0.85


def is_high_debt(row: FMLIRow) -> bool:
    """Low-mid income with debt-bearing signals.
    Without BSTREDC/BSTRINC dollar amounts in 2024 FMLI, using
    income proxy (low income + has installment loans inferred from
    high non-mortgage household debt). Coarser than the original
    debt-service-ratio definition; refine if doc24 surfaces."""
    inclass = _i(row, "INCLASS2")
    # OTHLOAN field exists; using as crude indicator
    has_other_loan = _f(row, "OTHLOAN", 0.0) > 0
    return inclass <= 5 and has_other_loan


def is_overspender(row: FMLIRow) -> bool:
    """Spending exceeds income (annualized). Mid-high income tier
    so this is distinct from paycheck_to_paycheck."""
    inclass = _i(row, "INCLASS2")
    eti = _f(row, "expense_to_income", 0.0)
    return inclass >= 5 and eti >= 1.05


def is_recent_grad(row: FMLIRow) -> bool:
    """Young, college-educated, single-person household."""
    age = _i(row, "AGE_REF")
    educ = _i(row, "EDUC_REF")
    fam = _i(row, "FAM_SIZE")
    return 22 <= age <= 30 and educ >= 13 and fam == 1


def is_freelancer(row: FMLIRow) -> bool:
    """Has business income, mid-tier salary.
    Without BUS_INCM amount, distinguished by FSALARYX tier:
    freelancer = has business income + moderate salary ($25-75k)."""
    return _has_business(row) and 25000 <= _f(row, "FSALARYX") <= 75000


def is_retired_fixed(row: FMLIRow) -> bool:
    """Retired (no earners), age 65+."""
    return _i(row, "AGE_REF") >= 65 and _i(row, "NO_EARNR") == 0


def is_dual_income(row: FMLIRow) -> bool:
    """Married with 2+ earners, working age."""
    married = _i(row, "MARITAL1") == 1
    earners = _i(row, "NO_EARNR")
    age = _i(row, "AGE_REF")
    return married and earners >= 2 and 30 <= age <= 60


def is_gig_worker(row: FMLIRow) -> bool:
    """Has business income, low salary (gig as supplemental income)."""
    return _has_business(row) and _f(row, "FSALARYX") < 25000


def is_small_biz_owner(row: FMLIRow) -> bool:
    """Has business income, high salary (business is primary income)."""
    return _has_business(row) and _f(row, "FSALARYX") >= 75000


# ── Registry ─────────────────────────────────────────────────────
# Households can satisfy multiple predicates; aggregation samples
# each household's spending into every archetype it qualifies for.
# This boosts sample size for under-represented archetypes and is
# academically defensible since archetypes are not exclusive
# segments — they're modeled behavior patterns.
ARCHETYPE_FILTERS: dict[str, Callable[[FMLIRow], bool]] = {
    "high_earner":          is_high_earner,
    "paycheck_to_paycheck": is_paycheck_to_paycheck,
    "high_debt":            is_high_debt,
    "overspender":          is_overspender,
    "recent_grad":          is_recent_grad,
    "freelancer":           is_freelancer,
    "retired_fixed":        is_retired_fixed,
    "dual_income":          is_dual_income,
    "gig_worker":           is_gig_worker,
    "small_biz_owner":      is_small_biz_owner,
}


MIN_HOUSEHOLDS_PER_ARCHETYPE = 50
