"""
UCC -> Artha banking category mapping.

BLS Universal Classification Codes (UCCs) are 6-digit category codes
used in the Consumer Expenditure Survey. This module maps UCC codes
to the 15 Artha banking categories used by generate_artha_data_v2.py.

Two design decisions:
  1. EXCLUDE non-consumer-spending UCCs rather than force-map them.
     BLS CES measures *total household expenditure* — including
     pension contributions, life insurance premiums, cash gifts,
     occupational expenses, etc. Those distort archetype spending
     patterns when carried into the calibration. Excluding them
     gives the cleaner academic framing: "calibrated against US
     consumer discretionary spending" not "total household
     expenditure".
  2. Map by UCC prefix at the BLS Stub-Level-3 / -4 boundary
     (3-4 digit prefixes). Coarse enough to be stable across
     years, granular enough to distinguish food-at-home from
     food-away-from-home.

Verified against intrvw24 PUMD (2024 reference year). Re-validate
when a future year ships if column / UCC structure changes.
"""

from __future__ import annotations

ARTHA_CATEGORIES = (
    "FOOD_AND_DRINK",
    "GROCERIES",
    "SHOPPING",
    "ENTERTAINMENT",
    "TRAVEL",
    "PERSONAL_CARE",
    "BILLS_UTILITIES",
    "LOAN_PAYMENT",
    "TRANSPORTATION",
    "INVESTMENTS",
    "HOUSING",
    "HEALTHCARE",
    "CHILDCARE",
    "TAXES",
    "MARKETING",
)


# ── UCC prefix -> Artha category ─────────────────────────────────
# Longest-prefix match wins. Source: BLS CES Stub File structure.
UCC_PREFIX_MAP: dict[str, str] = {
    # Food at home (groceries) — UCCs 190xxx-191xxx
    "190": "GROCERIES",
    "191": "GROCERIES",
    # Food away from home — 192xxx-193xxx
    "192": "FOOD_AND_DRINK",
    "193": "FOOD_AND_DRINK",
    # Alcoholic beverages — 200xxx (small share; group with FOOD_AND_DRINK)
    "200": "FOOD_AND_DRINK",

    # Shelter — 21xxxx (rent, mortgage interest, prop tax, repairs)
    "210": "HOUSING",
    "211": "HOUSING",
    "212": "HOUSING",
    "213": "HOUSING",

    # Utilities + fuels — 22xxxx (electricity, gas, water, phone, etc.)
    "220": "BILLS_UTILITIES",
    "221": "BILLS_UTILITIES",
    "222": "BILLS_UTILITIES",
    "223": "BILLS_UTILITIES",
    "224": "BILLS_UTILITIES",

    # Household operations + supplies — 23xxxx (cleaning, postage, etc.)
    "230": "HOUSING",
    # Furnishings + equipment — 24xxxx-29xxxx
    "240": "HOUSING",
    "250": "HOUSING",
    "260": "HOUSING",
    "270": "HOUSING",
    "280": "HOUSING",
    "290": "HOUSING",

    # Apparel — 30xxxx-37xxxx (men, women, children, footwear, accessories)
    "30":  "SHOPPING",
    "31":  "SHOPPING",
    "32":  "SHOPPING",
    "33":  "SHOPPING",
    "34":  "SHOPPING",
    "35":  "SHOPPING",
    "36":  "SHOPPING",
    "37":  "SHOPPING",

    # Transportation — 40xxxx-53xxxx
    "40":  "TRANSPORTATION",  # vehicles + parts
    "41":  "TRANSPORTATION",
    "42":  "TRANSPORTATION",
    "43":  "TRANSPORTATION",
    "44":  "TRANSPORTATION",
    "45":  "TRANSPORTATION",  # gasoline + motor oil
    "46":  "TRANSPORTATION",  # vehicle finance charges
    "47":  "TRANSPORTATION",  # vehicle maintenance
    "48":  "TRANSPORTATION",  # vehicle insurance
    "49":  "TRANSPORTATION",  # vehicle rental, licenses
    "50":  "TRANSPORTATION",
    "51":  "TRANSPORTATION",
    "52":  "TRANSPORTATION",
    "53":  "TRAVEL",          # public transportation incl. air, intercity bus
                              # Treating as TRAVEL since intercity matches
                              # the v2 archetype's TRAVEL category usage.

    # Healthcare — 54xxxx-58xxxx
    "54":  "HEALTHCARE",
    "55":  "HEALTHCARE",
    "56":  "HEALTHCARE",
    "57":  "HEALTHCARE",
    "58":  "HEALTHCARE",

    # Entertainment — 60xxxx-63xxxx (fees, equipment, services, audio/visual)
    "60":  "ENTERTAINMENT",
    "61":  "ENTERTAINMENT",
    "62":  "ENTERTAINMENT",
    "63":  "ENTERTAINMENT",

    # Personal care — 65xxxx
    "65":  "PERSONAL_CARE",

    # Reading — 66xxxx (books, magazines)
    "66":  "ENTERTAINMENT",

    # Education — 67xxxx, 85xxxx (tuition, supplies, fees)
    # No EDUCATION category in v2 — closest fit is SHOPPING (discretionary).
    "67":  "SHOPPING",
    "85":  "SHOPPING",

    # Tobacco / smoking supplies — 68xxxx, 83xxxx
    "68":  "PERSONAL_CARE",
    "83":  "PERSONAL_CARE",

    # Misc personal services that map to consumer behavior — 69xxxx, 70xxxx
    "69":  "PERSONAL_CARE",
    "70":  "PERSONAL_CARE",
}


# ── UCCs to EXCLUDE entirely ─────────────────────────────────────
# These are real BLS expenditure rows but not "consumer spending"
# in the personal-finance-app sense. Skipping them at extract time
# avoids inflating the calibrated category distributions.
UCC_EXCLUDE_PREFIXES: set[str] = {
    # Personal insurance premiums (life, supplemental health) — 72xxxx, 80xxxx
    # BLS counts as expenditure; for consumer-spending calibration these
    # are wealth-management decisions, not transactional spending.
    "72",
    "80",

    # Cash contributions / gifts to other households / charity — 79xxxx
    "79",

    # Occupational expenses (union dues, work uniforms, tools) — 87xxxx
    "87",

    # Pensions / retirement / Social Security contributions — 90xxxx, 91xxxx
    # These are long-horizon wealth transfers, not consumer transactions.
    # Note: this excludes them from INVESTMENTS calibration — fine, since
    # the v2 INVESTMENTS category models brokerage activity not pension
    # auto-deductions. INVESTMENTS stays on hardcoded archetype defaults.
    "90",
    "91",

    # Lump-sum life events captured separately — 88xxxx, 89xxxx
    "88",
    "89",
}


UCC_OVERRIDES: dict[str, str] = {
    # Add specific UCC overrides here as discovered.
}


def _is_excluded(ucc_str: str) -> bool:
    for length in (2, 3, 4):
        if ucc_str[:length] in UCC_EXCLUDE_PREFIXES:
            return True
    return False


def map_ucc(ucc: str | int) -> str | None:
    """Return Artha category for a 6-digit UCC code.
    Returns None if the UCC is excluded (non-consumer-spending) or
    unmapped. Caller drops None rows."""
    s = str(ucc).zfill(6)
    if _is_excluded(s):
        return None
    if s in UCC_OVERRIDES:
        return UCC_OVERRIDES[s]
    for length in (6, 5, 4, 3, 2):
        prefix = s[:length]
        if prefix in UCC_PREFIX_MAP:
            return UCC_PREFIX_MAP[prefix]
    return None


# Categories with no good CES analog. Stay on hardcoded archetype
# defaults from generate_artha_data_v2.py; not calibrated. Note in
# paper §6 Limitations.
UNCALIBRATED_CATEGORIES = (
    "LOAN_PAYMENT",   # CES tracks debt service in a separate FAM
                      #   section; not in MTBI by UCC.
    "INVESTMENTS",    # Pensions excluded above; brokerage activity
                      #   not in CES. Stays hardcoded.
    "CHILDCARE",      # Childcare in a different survey table (CU).
    "TAXES",          # Taxes deducted from income, not in MTBI.
    "MARKETING",      # No CES analog (small_biz_owner only).
)
