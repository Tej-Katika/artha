# Investments data fetchers

ETL scripts that populate the investments-domain reference data
(securities, daily prices, risk-free rate) from Yahoo Finance.

## Setup

```powershell
py -3 -m pip install -r data/fetchers/requirements.txt
```

## Run

```powershell
# Full 51-ticker fetch (≈5 minutes, ≈100 MB cache):
py -3 data/fetchers/fetch_yahoo.py

# Quick smoke (first 3 tickers):
py -3 data/fetchers/fetch_yahoo.py --limit 3
```

Outputs land under `data/investments/` (gitignored, regenerable):

```
data/investments/
├── prices/
│   ├── AAPL.csv
│   ├── NVDA.csv
│   └── ... (51 files: date,open,high,low,close,adj_close,volume)
└── macro/
    └── dgs10.csv  (date,dgs10_pct — derived from ^TNX)
```

## Load into Postgres

After the fetch finishes:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--load-investments-data"
```

The `InvestmentsDataLoader` Spring component reads the CSVs and
upserts into the `securities`, `daily_prices`, and `risk_free_rate`
tables. Idempotent on re-run: existing rows are updated rather than
duplicated.

## Generate synthetic portfolios

After securities + daily_prices are loaded, hydrate the portfolio
ontology (50 portfolios across 10 archetypes, ~230 positions and
trades, ~90 fee rows, 6 benchmarks):

```powershell
py -3 data/fetchers/generate_investments.py
```

Idempotent: re-running deletes any prior `INV-V2 *` portfolios first
and re-seeds. The script also emits `data/investments/users_v2.json`
(gitignored) — a manifest of `{user_id, portfolio_id, archetype}`
records consumed by downstream eval tooling.

Per-archetype calibration anchors come from Vanguard *How America
Saves* 2024 (median balance, holdings count, equity allocation by
age band); see `research/ONTOLOGY_V2_SPEC.md` §9.3 for the full
table of anchors used.

## Source of truth

The 50-ticker scope (plus `^TNX`) is defined in
`src/main/resources/investments/tickers.json`. Edit there if the
universe needs to change; both the Python fetcher and the Java loader
read from that single file.
