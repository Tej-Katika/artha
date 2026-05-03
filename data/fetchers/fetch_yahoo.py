"""Fetch daily OHLCV history for the investments-domain ticker
universe from Yahoo Finance via the yfinance library.

Reads `src/main/resources/investments/tickers.json` (51 entries: 50
tradable securities + ^TNX as the risk-free-rate proxy), writes one
CSV per ticker to `data/investments/prices/<TICKER>.csv` with the
schema:

    date,open,high,low,close,adj_close,volume

For ^TNX a second CSV is emitted at `data/investments/macro/dgs10.csv`
containing `date,dgs10_pct` for the InvestmentsDataLoader to load
into the risk_free_rate table.

Usage:
    py -3 data/fetchers/fetch_yahoo.py [--period 5y] [--limit N]

The --limit flag is a development convenience that stops after N
tickers (e.g., for a quick smoke run before a full 51-ticker fetch).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import yfinance as yf

REPO_ROOT      = Path(__file__).resolve().parents[2]
TICKERS_PATH   = REPO_ROOT / "src" / "main" / "resources" / "investments" / "tickers.json"
PRICES_DIR     = REPO_ROOT / "data" / "investments" / "prices"
MACRO_DIR      = REPO_ROOT / "data" / "investments" / "macro"

# Empirically: yfinance batches ~50 tickers per request. A small inter-
# request sleep keeps us under Yahoo's per-IP rate limit on retries.
BATCH_SIZE     = 25
INTER_BATCH_S  = 1.0


def load_tickers() -> list[dict]:
    with TICKERS_PATH.open("r", encoding="utf-8") as f:
        return json.load(f)


def safe_filename(ticker: str) -> str:
    # Yahoo tickers can contain '^' (e.g. ^TNX) and '-'; both are
    # filesystem-safe on Windows but '^' confuses some shells. Strip.
    return ticker.replace("^", "").replace("/", "_")


def fetch_one(ticker: str, period: str) -> "yf.Ticker":
    return yf.Ticker(ticker).history(
        period=period, auto_adjust=False, actions=False)


def write_prices_csv(ticker: str, df, out_dir: Path) -> int:
    if df is None or df.empty:
        print(f"  [{ticker}] no data returned")
        return 0
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{safe_filename(ticker)}.csv"
    # Normalize column names; some yfinance versions return MultiIndex
    df = df.rename(columns={
        "Open": "open", "High": "high", "Low": "low",
        "Close": "close", "Adj Close": "adj_close", "Volume": "volume",
    })
    keep = [c for c in ["open", "high", "low", "close", "adj_close", "volume"]
            if c in df.columns]
    df = df[keep]
    df.index.name = "date"
    df.to_csv(out, date_format="%Y-%m-%d")
    return len(df)


def write_dgs10_csv(df, out_dir: Path) -> int:
    """The ^TNX 'close' column is the 10-yr Treasury yield in percent
    (e.g., 4.25). Re-emit as a two-column CSV the Java loader can
    map directly to risk_free_rate(rate_date, dgs10_pct)."""
    if df is None or df.empty:
        return 0
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / "dgs10.csv"
    series = df.rename(columns={"Close": "dgs10_pct"})[["dgs10_pct"]]
    series.index.name = "date"
    series.to_csv(out, date_format="%Y-%m-%d")
    return len(series)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--period", default="5y",
                        help="yfinance period (default: 5y)")
    parser.add_argument("--limit", type=int, default=None,
                        help="stop after N tickers (dev convenience)")
    args = parser.parse_args()

    tickers = load_tickers()
    if args.limit is not None:
        tickers = tickers[: args.limit]

    print(f"Fetching {len(tickers)} ticker(s) for period={args.period}")
    PRICES_DIR.mkdir(parents=True, exist_ok=True)

    total_rows = 0
    for i, t in enumerate(tickers, 1):
        sym = t["ticker"]
        print(f"[{i}/{len(tickers)}] {sym}...", end=" ", flush=True)
        try:
            df = fetch_one(sym, args.period)
            rows = write_prices_csv(sym, df, PRICES_DIR)
            print(f"{rows} rows")
            total_rows += rows

            if sym == "^TNX":
                write_dgs10_csv(df, MACRO_DIR)
        except Exception as ex:           # noqa: BLE001
            print(f"ERROR: {ex}")
        if i % BATCH_SIZE == 0:
            time.sleep(INTER_BATCH_S)

    print(f"\nDone. {total_rows} total rows across {len(tickers)} tickers.")
    print(f"Prices -> {PRICES_DIR}")
    print(f"Macro  -> {MACRO_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
