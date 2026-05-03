# Database design

PostgreSQL schema reference for the Artha agent platform. The
authoritative definitions live in
`src/main/resources/db/migration/` — this document is a high-level
map for navigating them.

## Migrations

| Version | File | Adds |
|---|---|---|
| V1 | `V1__initial_schema.sql` | Banking ontology (users, bank_accounts, transactions, transaction_enrichments, merchant_profiles, merchant_types, spending_categories, classification_rules, recurring_bills, budgets, financial_goals). |
| V2 | `V2__action_audit.sql` | `action_audit` — append-only Hoare-triple audit log for state-changing actions. |
| V2 | `V2__violation_log.sql` | `violation_log` — telemetry for constraint violations and repair outcomes. |
| V3 | `V3__provenance_on_enrichment.sql` | Adds provenance columns to `transaction_enrichments` (source, confidence, combiner). |
| V4 | `V4__investments_ontology.sql` | Investments domain: securities, daily_prices, risk_free_rate, portfolios, positions, lots, trades, dividends, fees, risk_profiles, benchmarks. |

Flyway is disabled (`spring.flyway.enabled: false` in
`application.yml`); apply migrations manually with `psql -f`.
`ddl-auto` is set to `none` so the SQL files are the single source
of truth for schema.

## Banking ontology (V1)

Nine ontology object types, all keyed by `UUID` and FK-linked to
`users(id)` with `ON DELETE CASCADE`.

| Table | Purpose |
|---|---|
| `users` | Identity. Email + full_name only. |
| `bank_accounts` | Linked institutions; one row per account. |
| `transactions` | Raw, source-of-truth transaction events. Indexed on `(user_id, post_date)`. |
| `transaction_enrichments` | Typed annotations on a transaction (1:1): category, merchant profile, anomaly score, recurring-bill linkage. V3 adds provenance columns. |
| `merchant_profiles` / `merchant_types` | Canonical merchant identity (e.g., "Whole Foods" → `MerchantType.GROCERY`). Used by the agent's category/anomaly tools to classify spending consistently. |
| `spending_categories` | Per-user category taxonomy (Groceries, Dining, etc.). |
| `classification_rules` | User-overridable rules for merchant → category mapping. |
| `recurring_bills` | Detected subscriptions; one row per cadence. |
| `budgets` | Per-category monthly limits with rollover semantics. |
| `financial_goals` | Savings / debt-payoff goals with target amount and date. |

## v2 cross-cutting tables (V2 / V3)

These tables are domain-agnostic — they record events from any
domain via a `domain VARCHAR(20)` column.

| Table | Purpose |
|---|---|
| `action_audit` | One row per Action invocation, regardless of outcome. Columns: `action_name`, `domain`, `actor` (AGENT/USER/SYSTEM), `outcome` (SUCCESS / FAILURE_PRECONDITION / FAILURE_EXECUTION / FAILURE_POSTCONDITION / ROLLED_BACK), `input_json`, `output_json`, `error_message`, `started_at`, `ended_at`. |
| `violation_log` | One row per constraint violation. Columns: `constraint_name`, `domain`, `grade` (HARD/SOFT/ADVISORY), `message`, `repair_hint`, `repaired` (set after the orchestrator's retry loop terminates), `observed_at`. |

Provenance is stored inline on `transaction_enrichments` (V3): a
provenance value object captures the source set, combiner that
produced the fact, and a calibrated confidence in `[0, 1]`.

## Investments ontology (V4)

Eleven tables: nine ontology types plus two reference-data tables
(daily_prices, risk_free_rate).

| Table | Purpose |
|---|---|
| `securities` | Tradable universe (50 equities/ETFs + `^TNX` for the risk-free rate). Populated from `src/main/resources/investments/tickers.json`. |
| `daily_prices` | OHLCV per (security, date). Composite primary key. Populated from Yahoo Finance via `data/fetchers/fetch_yahoo.py`. |
| `risk_free_rate` | Daily 10-year Treasury yield (DGS10 equivalent), keyed by date. |
| `portfolios` | Root entity per portfolio. `archetype` lives on the portfolio so a single user can hold multiple archetype portfolios. |
| `positions` | Aggregated current holding (one row per portfolio × security). |
| `lots` | Tax-lot granularity. Each BUY trade opens a new lot; SELL trades close lots according to `lot_method` (FIFO / LIFO / SPEC_ID). |
| `trades` | Append-only trade record. Corrections write a reversing trade rather than UPDATEing this row. |
| `dividends` | Cash-dividend events received on a position. |
| `fees` | Cost-basis-eroding events (ADVISORY / EXPENSE_RATIO / COMMISSION / SLIPPAGE). |
| `risk_profiles` | One row per portfolio (UNIQUE constraint). Target equity / bond / alt allocation must sum to 100% ± 0.5; max-drawdown tolerance bounded `[0, 100]`. |
| `benchmarks` | Reference indices for portfolio_health comparisons (SPY, AGG, IEFA, BITO, XLK, ICLN). |

Money columns use `NUMERIC(19, 4)` to avoid float drift. Quantities
use `NUMERIC(19, 8)` to support fractional shares and crypto.
Timestamps are `TIMESTAMPTZ`; calendar days (trade dates, dividend
ex-dates, fee periods, daily-price dates) are `DATE`.

## Indexing

All FKs and natural-search columns have explicit `idx_<table>_<cols>`
indexes. The hottest paths are:

- `idx_transactions_user_post_date` — every banking spending query
  scans this.
- `idx_action_audit_session` — used by the agent loop to look up the
  current session's actions.
- `idx_daily_prices_date` — covers historical-price scans for
  portfolio_health.
- `idx_trades_portfolio_executed` — covers behavioral-bias and
  fee-audit queries.

## Data lifecycle

| Direction | Path |
|---|---|
| Banking ingest | bank API or `generate_artha_data_v2.py` → `transactions` → `EnrichmentService` writes `transaction_enrichments` (with provenance) → `RecurringBillDetector` populates `recurring_bills` |
| Banking write actions | Agent → tool → `ActionExecutor` (precondition check, transactional run, postcondition check) → ontology mutation + `action_audit` row |
| Investments ingest | `data/fetchers/fetch_yahoo.py` → CSV cache → `InvestmentsDataLoader` (`--load-investments-data` flag) → `securities`, `daily_prices`, `risk_free_rate` |
| Investments synth | `data/fetchers/generate_investments.py` → `portfolios`, `positions`, `trades`, `fees`, `risk_profiles`, `benchmarks` |
| Constraint check | After every agent end-of-turn → `ClaimExtractor` extracts factual claims → `ConstraintChecker` evaluates → `ViolationLog` rows persisted via `REQUIRES_NEW` → `repaired` stamped at session end |

For the read-side surface, see the typed agent tools under
`src/main/java/com/artha/banking/tools/` and the orchestrator at
`src/main/java/com/artha/core/agent/AgentOrchestrator.java`.
