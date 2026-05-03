"""Synthesize the investments-domain portfolio dataset.

For each of 10 archetypes (mirroring the banking archetypes), this
script attaches one synthetic portfolio to each of 5 existing users,
producing 50 portfolios total. Each portfolio gets:

  * A `portfolios` row tagged with the archetype
  * A `risk_profiles` row whose target_equity / bond / alt allocations
    follow archetype rules (and are calibrated against Vanguard
    *How America Saves* 2024 anchors; see ONTOLOGY_V2_SPEC §9.3)
  * 3-7 `positions` selected from the archetype's allowed ticker set
  * One opening BUY `trade` per position, executed at the security's
    earliest price in the daily_prices table
  * Two `fees` rows per portfolio (annualized) reflecting the
    archetype's cost structure
  * A small set of `benchmarks` rows shared across the run

The script is idempotent: portfolios from prior runs (matched by
"name = 'INV-V2 <archetype> <user_short>'") are deleted first, so
re-running re-seeds without manual cleanup.

Outputs:

  * Inserts into Postgres tables (portfolios, risk_profiles,
    positions, trades, fees, benchmarks)
  * `data/investments/users_v2.json` — list of
    {user_id, portfolio_id, archetype} for downstream eval tooling

Usage:

    py -3 data/fetchers/generate_investments.py
        [--per-archetype 5]      # users per archetype (default 5)
        [--seed 1729]            # deterministic random seed

Database connection comes from `application.yml` defaults; override
via PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE env vars.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import uuid
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import psycopg2
import psycopg2.extras

REPO_ROOT       = Path(__file__).resolve().parents[2]
USERS_OUT_PATH  = REPO_ROOT / "data" / "investments" / "users_v2.json"

# ── archetype rules ──────────────────────────────────────────────
# Allocation tuples are (equity_pct, bond_pct, alt_pct) — must sum
# to 100. ticker_pool selects from src/main/resources/investments/
# tickers.json. n_positions is the count of holdings synthesized
# per portfolio. fees_annual_pct is applied as ADVISORY for the
# managed archetypes and EXPENSE_RATIO for index/crypto.

ARCHETYPES: dict[str, dict[str, Any]] = {
    "aggressive_growth": {
        "alloc": (90, 0, 10), "max_dd": 50, "n_positions": 6,
        "ticker_pool": ["NVDA", "AAPL", "MSFT", "META", "GOOGL", "AMZN", "TSLA", "AVGO"],
        "fees": [("EXPENSE_RATIO", 0.05)],
    },
    "conservative_retiree": {
        "alloc": (30, 60, 10), "max_dd": 15, "n_positions": 5,
        "ticker_pool": ["KO", "PG", "JNJ", "T", "MO", "ED", "GIS", "KMB", "^TNX"],
        "fees": [("ADVISORY", 0.50)],
    },
    "dividend_income": {
        "alloc": (70, 25, 5), "max_dd": 25, "n_positions": 7,
        "ticker_pool": ["PEP", "MMM", "CL", "MCD", "MO", "T", "IBM", "ABBV", "JNJ", "PG"],
        "fees": [("ADVISORY", 0.25), ("EXPENSE_RATIO", 0.10)],
    },
    "day_trader_momentum": {
        "alloc": (95, 0, 5), "max_dd": 60, "n_positions": 5,
        "ticker_pool": ["NVDA", "TSLA", "MARA", "COIN", "RIOT", "MSTR", "AVGO"],
        "fees": [("COMMISSION", 0.00)],   # synthesized per-trade below
    },
    "index_only_passive": {
        "alloc": (80, 20, 0), "max_dd": 30, "n_positions": 3,
        "ticker_pool": ["ICLN", "AAPL", "MSFT", "AMZN"],   # ICLN as ETF stand-in
        "fees": [("EXPENSE_RATIO", 0.04)],
    },
    "concentrated_single_stock": {
        "alloc": (100, 0, 0), "max_dd": 70, "n_positions": 1,
        "ticker_pool": ["NVDA", "TSLA", "AAPL", "MSFT"],
        "fees": [],
    },
    "esg_focused": {
        "alloc": (80, 15, 5), "max_dd": 35, "n_positions": 5,
        "ticker_pool": ["ENPH", "FSLR", "NEE", "BEP", "ICLN"],
        "fees": [("ADVISORY", 0.40), ("EXPENSE_RATIO", 0.46)],
    },
    "robo_advisor_mimic": {
        "alloc": (70, 25, 5), "max_dd": 25, "n_positions": 5,
        "ticker_pool": ["AAPL", "MSFT", "NVDA", "JPM", "WMT", "JNJ"],
        "fees": [("ADVISORY", 0.25)],
    },
    "options_heavy": {
        "alloc": (60, 0, 40), "max_dd": 50, "n_positions": 4,
        "ticker_pool": ["MSTR", "COIN", "AVGO", "TSLA", "META"],
        "fees": [("COMMISSION", 0.00)],
    },
    "crypto_heavy_mixed": {
        "alloc": (40, 0, 60), "max_dd": 80, "n_positions": 5,
        "ticker_pool": ["COIN", "MSTR", "MARA", "RIOT", "BITO"],
        "fees": [("EXPENSE_RATIO", 1.00)],
    },
}

BENCHMARKS = [
    ("S&P 500 Total Return",       "SPY",     "BROAD"),
    ("Bloomberg US Aggregate Bond","AGG",     "BOND"),
    ("MSCI EAFE",                  "IEFA",    "BROAD"),
    ("Bitcoin (futures ETF)",      "BITO",    "CRYPTO"),
    ("S&P 500 Information Tech",   "XLK",     "SECTOR"),
    ("iShares Clean Energy",       "ICLN",    "SECTOR"),
]

# ── DB helpers ───────────────────────────────────────────────────

def connect():
    return psycopg2.connect(
        host=     os.environ.get("PGHOST",     "localhost"),
        port= int(os.environ.get("PGPORT",     "5432")),
        user=     os.environ.get("PGUSER",     "postgres"),
        password= os.environ.get("PGPASSWORD", "finwise123"),
        dbname=   os.environ.get("PGDATABASE", "postgres"),
    )


def fetch_user_ids(cur, n: int) -> list[str]:
    cur.execute("SELECT id FROM users ORDER BY id LIMIT %s", (n,))
    return [str(r[0]) for r in cur.fetchall()]


def fetch_security_lookup(cur) -> dict[str, dict]:
    """Return ticker → {id, asset_class}."""
    cur.execute("SELECT id, ticker, asset_class FROM securities")
    return {row[1]: {"id": str(row[0]), "asset_class": row[2]}
            for row in cur.fetchall()}


def fetch_first_price(cur, security_id: str) -> tuple[date, Decimal] | None:
    cur.execute(
        "SELECT price_date, close_price FROM daily_prices "
        "WHERE security_id = %s ORDER BY price_date ASC LIMIT 1",
        (security_id,))
    row = cur.fetchone()
    return (row[0], row[1]) if row else None


def delete_prior_v2_portfolios(cur) -> int:
    cur.execute("DELETE FROM portfolios WHERE name LIKE 'INV-V2 %' RETURNING 1")
    return cur.rowcount


def upsert_benchmarks(cur) -> None:
    sql = ("INSERT INTO benchmarks (id, name, ticker, category) "
           "VALUES (%s, %s, %s, %s) ON CONFLICT (ticker) "
           "DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category")
    for name, ticker, category in BENCHMARKS:
        cur.execute(sql, (str(uuid.uuid4()), name, ticker, category))


# ── per-portfolio synthesis ──────────────────────────────────────

def short_uid(u: str) -> str:
    return u.split("-")[0]


def pick_tickers(rng: random.Random, archetype: str, n: int,
                 universe: dict[str, dict]) -> list[str]:
    pool  = [t for t in ARCHETYPES[archetype]["ticker_pool"] if t in universe]
    n     = min(n, len(pool))
    return rng.sample(pool, n) if n < len(pool) else pool[:n]


def even_weights(n: int) -> list[float]:
    return [1.0 / n] * n


def build_portfolio(cur, rng: random.Random, user_id: str, archetype: str,
                    universe: dict[str, dict],
                    initial_value: Decimal) -> dict:
    rules = ARCHETYPES[archetype]
    portfolio_id = str(uuid.uuid4())

    cur.execute(
        "INSERT INTO portfolios (id, user_id, name, archetype, base_currency, "
        "                         opened_at) "
        "VALUES (%s, %s, %s, %s, 'USD', now() - interval '24 months')",
        (portfolio_id, user_id, f"INV-V2 {archetype} {short_uid(user_id)}",
         archetype))

    eq, bd, alt = rules["alloc"]
    cur.execute(
        "INSERT INTO risk_profiles (id, portfolio_id, target_equity_pct, "
        "    target_bond_pct, target_alt_pct, max_drawdown_tolerance_pct) "
        "VALUES (%s, %s, %s, %s, %s, %s)",
        (str(uuid.uuid4()), portfolio_id, eq, bd, alt, rules["max_dd"]))

    tickers = pick_tickers(rng, archetype, rules["n_positions"], universe)
    weights = even_weights(len(tickers))
    positions, trades = 0, 0

    for tkr, w in zip(tickers, weights):
        sec = universe[tkr]
        first = fetch_first_price(cur, sec["id"])
        if first is None:
            continue
        first_date, first_close = first
        target_dollars = initial_value * Decimal(w)
        quantity = (target_dollars / first_close).quantize(Decimal("0.00000001"))
        if quantity <= 0:
            continue

        position_id = str(uuid.uuid4())
        cur.execute(
            "INSERT INTO positions (id, portfolio_id, security_id, quantity, "
            "    avg_cost, opened_at) "
            "VALUES (%s, %s, %s, %s, %s, %s)",
            (position_id, portfolio_id, sec["id"], quantity, first_close,
             datetime.combine(first_date, datetime.min.time())))
        positions += 1

        cur.execute(
            "INSERT INTO trades (id, portfolio_id, security_id, side, quantity, "
            "    price, fees, executed_at, provenance_json) "
            "VALUES (%s, %s, %s, 'BUY', %s, %s, 0, %s, %s)",
            (str(uuid.uuid4()), portfolio_id, sec["id"], quantity, first_close,
             datetime.combine(first_date, datetime.min.time()),
             json.dumps({"source": "GENERATOR", "version": "v2",
                         "archetype": archetype})))
        trades += 1

    # Annualized fees, two consecutive 1-year periods (years -2 and -1)
    fees_added = 0
    for kind, pct in rules["fees"]:
        annual_amount = (initial_value * Decimal(pct) / Decimal(100)).quantize(
            Decimal("0.0001"))
        if annual_amount <= 0: continue
        for years_back in (2, 1):
            start = date.today().replace(year=date.today().year - years_back)
            end   = start.replace(year=start.year + 1) - timedelta(days=1)
            cur.execute(
                "INSERT INTO fees (id, portfolio_id, kind, amount, "
                "    period_start, period_end, notes) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                (str(uuid.uuid4()), portfolio_id, kind, annual_amount,
                 start, end, f"synthesized: {pct}% of {initial_value}"))
            fees_added += 1

    return {"portfolio_id": portfolio_id, "positions": positions,
            "trades": trades, "fees": fees_added}


def initial_value_for(rng: random.Random, archetype: str) -> Decimal:
    """Calibrated against Vanguard *How America Saves* 2024 anchors
    (median ~$28k, mean ~$112k; conservative_retiree starts higher)."""
    if archetype == "conservative_retiree":
        return Decimal(rng.randint(400_000, 800_000))
    if archetype == "concentrated_single_stock":
        return Decimal(rng.randint(50_000, 250_000))
    return Decimal(rng.randint(28_000, 200_000))


# ── orchestration ────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--per-archetype", type=int, default=5)
    parser.add_argument("--seed",          type=int, default=1729)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    n_users_total = args.per_archetype * len(ARCHETYPES)

    with connect() as conn:
        with conn.cursor() as cur:
            user_ids = fetch_user_ids(cur, n_users_total)
            if len(user_ids) < n_users_total:
                print(f"ERROR: only {len(user_ids)} users in DB, need "
                      f"{n_users_total}", file=sys.stderr)
                return 1

            universe = fetch_security_lookup(cur)
            if not universe:
                print("ERROR: securities table is empty - run "
                      "InvestmentsDataLoader first", file=sys.stderr)
                return 1

            removed = delete_prior_v2_portfolios(cur)
            if removed:
                print(f"Cleared {removed} prior INV-V2 portfolio(s)")
            upsert_benchmarks(cur)
            print(f"Benchmarks ensured: {len(BENCHMARKS)}")

            results: dict[str, list[dict]] = defaultdict(list)
            user_idx = 0
            for archetype in ARCHETYPES.keys():
                for _ in range(args.per_archetype):
                    user_id = user_ids[user_idx]
                    user_idx += 1
                    out = build_portfolio(
                        cur, rng, user_id, archetype, universe,
                        initial_value_for(rng, archetype))
                    out["user_id"]   = user_id
                    out["archetype"] = archetype
                    results[archetype].append(out)

            conn.commit()

    # Calibration sanity print
    print()
    print(f"{'archetype':<28} {'n':>3} {'avg pos':>8} {'avg trd':>8} {'avg fee':>8}")
    print("-" * 64)
    grand_pos, grand_trd, grand_fee = 0, 0, 0
    for archetype, lst in results.items():
        n = len(lst)
        avg_pos = sum(r["positions"] for r in lst) / n if n else 0
        avg_trd = sum(r["trades"]    for r in lst) / n if n else 0
        avg_fee = sum(r["fees"]      for r in lst) / n if n else 0
        grand_pos += sum(r["positions"] for r in lst)
        grand_trd += sum(r["trades"]    for r in lst)
        grand_fee += sum(r["fees"]      for r in lst)
        print(f"{archetype:<28} {n:>3} {avg_pos:>8.1f} {avg_trd:>8.1f} "
              f"{avg_fee:>8.1f}")
    print("-" * 64)
    total_n = sum(len(v) for v in results.values())
    print(f"{'TOTAL':<28} {total_n:>3} {grand_pos:>8} {grand_trd:>8} "
          f"{grand_fee:>8}")
    print()
    print(f"Vanguard HAS 2024 anchor: avg # of fund holdings 4-6 -- "
          f"observed avg positions: "
          f"{grand_pos / max(total_n, 1):.1f}")

    USERS_OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    out_records = [r for lst in results.values() for r in lst]
    USERS_OUT_PATH.write_text(json.dumps(out_records, indent=2),
                              encoding="utf-8")
    print(f"Wrote {USERS_OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
