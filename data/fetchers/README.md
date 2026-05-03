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

## Source of truth

The 50-ticker scope (plus `^TNX`) is defined in
`src/main/resources/investments/tickers.json`. Edit there if the
universe needs to change; both the Python fetcher and the Java loader
read from that single file.
