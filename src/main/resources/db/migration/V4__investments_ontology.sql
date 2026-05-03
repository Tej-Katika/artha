-- Artha v2 — Investments domain ontology (Week 7).
--
-- Run manually before activating the investments domain.
-- ddl-auto=none, Flyway disabled — this file is the source of truth
-- for the schema. Layout mirrors banking ontology conventions:
-- UUID PKs, NUMERIC(19,4) money columns, TIMESTAMPTZ for events,
-- DATE for trade/dividend calendar days, append-only trades.
--
-- Reference: research/ONTOLOGY_V2_SPEC.md §9.1.

-- ── reference: securities ──────────────────────────────────────────
-- Tradable universe. Populated from Yahoo Finance + SEC EDGAR.
-- The 50-ticker scope (§9.2) lives in
-- src/main/resources/investments/tickers.json.

CREATE TABLE IF NOT EXISTS securities (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticker               VARCHAR(16)  NOT NULL UNIQUE,
    name                 VARCHAR(200) NOT NULL,
    asset_class          VARCHAR(16)  NOT NULL,
    sector               VARCHAR(80),
    market_cap_bucket    VARCHAR(8),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_securities_asset_class CHECK (
        asset_class IN ('EQUITY', 'ETF', 'BOND', 'CRYPTO', 'COMMODITY')),
    CONSTRAINT chk_securities_market_cap CHECK (
        market_cap_bucket IS NULL
        OR market_cap_bucket IN ('LARGE', 'MID', 'SMALL', 'NA'))
);

CREATE INDEX IF NOT EXISTS idx_securities_asset_sector
    ON securities (asset_class, sector);

-- ── reference: daily_prices ───────────────────────────────────────
-- Composite PK on (security_id, date) keeps lookups O(log n) and
-- makes upserts (ON CONFLICT DO UPDATE) trivial during refetch.

CREATE TABLE IF NOT EXISTS daily_prices (
    security_id   UUID         NOT NULL REFERENCES securities(id),
    price_date    DATE         NOT NULL,
    open_price    NUMERIC(19,4),
    high_price    NUMERIC(19,4),
    low_price     NUMERIC(19,4),
    close_price   NUMERIC(19,4) NOT NULL,
    volume        BIGINT,
    adj_close     NUMERIC(19,4),

    PRIMARY KEY (security_id, price_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_prices_date
    ON daily_prices (price_date DESC);

-- ── reference: risk_free_rate ─────────────────────────────────────
-- Daily 10-year Treasury constant maturity rate (FRED series DGS10).
-- Single column for v2; v3+ may add DGS3MO and term-structure series.

CREATE TABLE IF NOT EXISTS risk_free_rate (
    rate_date    DATE         PRIMARY KEY,
    dgs10_pct    NUMERIC(7,4) NOT NULL
);

-- ── ontology: portfolios ──────────────────────────────────────────
-- Root entity per portfolio. archetype lives here so v3 can support
-- multiple portfolios per user (e.g., taxable + 401k + Roth).

CREATE TABLE IF NOT EXISTS portfolios (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    archetype       VARCHAR(40)  NOT NULL,
    base_currency   CHAR(3)      NOT NULL DEFAULT 'USD',
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_portfolios_user
    ON portfolios (user_id);

CREATE INDEX IF NOT EXISTS idx_portfolios_archetype
    ON portfolios (archetype);

-- ── ontology: positions ───────────────────────────────────────────
-- Aggregated current holding. One row per (portfolio, security).
-- Mutated by RecordTradeAction; read by every portfolio_summary tool.

CREATE TABLE IF NOT EXISTS positions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id    UUID          NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    security_id     UUID          NOT NULL REFERENCES securities(id),
    quantity        NUMERIC(19,8) NOT NULL,
    avg_cost        NUMERIC(19,4) NOT NULL,
    opened_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_positions_portfolio_security
        UNIQUE (portfolio_id, security_id),
    CONSTRAINT chk_positions_quantity_nonneg
        CHECK (quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_positions_portfolio
    ON positions (portfolio_id);

CREATE INDEX IF NOT EXISTS idx_positions_security
    ON positions (security_id);

-- ── ontology: lots ────────────────────────────────────────────────
-- Tax-lot granularity for cost-basis tracking. BUY trades open a new
-- lot; SELL trades close lots according to lot_method (FIFO default).

CREATE TABLE IF NOT EXISTS lots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id     UUID          NOT NULL REFERENCES positions(id) ON DELETE CASCADE,
    quantity        NUMERIC(19,8) NOT NULL,
    cost_basis      NUMERIC(19,4) NOT NULL,
    acquired_at     TIMESTAMPTZ   NOT NULL,
    closed_at       TIMESTAMPTZ,
    lot_method      VARCHAR(8)    NOT NULL DEFAULT 'FIFO',

    CONSTRAINT chk_lots_method CHECK (lot_method IN ('FIFO', 'LIFO', 'SPEC_ID')),
    CONSTRAINT chk_lots_quantity_nonneg CHECK (quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_lots_position_acquired
    ON lots (position_id, acquired_at);

-- ── ontology: trades ──────────────────────────────────────────────
-- Append-only trade record. Corrections are written as reversing
-- trades, never UPDATEd, so the audit trail is permanent.

CREATE TABLE IF NOT EXISTS trades (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id      UUID          NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    security_id       UUID          NOT NULL REFERENCES securities(id),
    side              VARCHAR(4)    NOT NULL,
    quantity          NUMERIC(19,8) NOT NULL,
    price             NUMERIC(19,4) NOT NULL,
    fees              NUMERIC(19,4) NOT NULL DEFAULT 0,
    executed_at       TIMESTAMPTZ   NOT NULL,
    provenance_json   TEXT,

    CONSTRAINT chk_trades_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_trades_quantity_pos CHECK (quantity > 0),
    CONSTRAINT chk_trades_price_pos    CHECK (price > 0)
);

CREATE INDEX IF NOT EXISTS idx_trades_portfolio_executed
    ON trades (portfolio_id, executed_at DESC);

CREATE INDEX IF NOT EXISTS idx_trades_security
    ON trades (security_id);

-- ── ontology: dividends ───────────────────────────────────────────
-- Cash-dividend events received on a position.

CREATE TABLE IF NOT EXISTS dividends (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id   UUID          NOT NULL REFERENCES positions(id) ON DELETE CASCADE,
    amount        NUMERIC(19,4) NOT NULL,
    currency      CHAR(3)       NOT NULL DEFAULT 'USD',
    ex_date       DATE          NOT NULL,
    paid_at       TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_dividends_amount_pos CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_dividends_position_paid
    ON dividends (position_id, paid_at DESC);

-- ── ontology: fees ────────────────────────────────────────────────
-- Cost-basis-eroding events partitioned by kind. Drives the fee_audit
-- query category and the FlagFeeOverpaymentAction.

CREATE TABLE IF NOT EXISTS fees (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id   UUID          NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    kind           VARCHAR(20)   NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,
    notes          TEXT,

    CONSTRAINT chk_fees_kind CHECK (
        kind IN ('ADVISORY', 'EXPENSE_RATIO', 'COMMISSION', 'SLIPPAGE')),
    CONSTRAINT chk_fees_period_order
        CHECK (period_end >= period_start),
    CONSTRAINT chk_fees_amount_pos
        CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_fees_portfolio_kind
    ON fees (portfolio_id, kind);

-- ── ontology: risk_profiles ───────────────────────────────────────
-- One row per portfolio. UNIQUE(portfolio_id) enforces 1:1.
-- Drives the PortfolioWeightSum HARD constraint.

CREATE TABLE IF NOT EXISTS risk_profiles (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id                UUID          NOT NULL UNIQUE
                                              REFERENCES portfolios(id) ON DELETE CASCADE,
    target_equity_pct           NUMERIC(5,2)  NOT NULL,
    target_bond_pct             NUMERIC(5,2)  NOT NULL,
    target_alt_pct              NUMERIC(5,2)  NOT NULL DEFAULT 0,
    max_drawdown_tolerance_pct  NUMERIC(5,2)  NOT NULL,
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Allocation must sum to 100% within float tolerance; the
    -- PortfolioWeightSum constraint catches drifts at runtime, but
    -- the schema enforces obviously-bad configurations at insert.
    CONSTRAINT chk_risk_profiles_alloc_bounds CHECK (
        target_equity_pct BETWEEN 0 AND 100
        AND target_bond_pct  BETWEEN 0 AND 100
        AND target_alt_pct   BETWEEN 0 AND 100
        AND target_equity_pct + target_bond_pct + target_alt_pct
            BETWEEN 99.5 AND 100.5),
    CONSTRAINT chk_risk_profiles_drawdown
        CHECK (max_drawdown_tolerance_pct BETWEEN 0 AND 100)
);

-- ── ontology: benchmarks ──────────────────────────────────────────
-- Reference indices for portfolio_health comparisons.

CREATE TABLE IF NOT EXISTS benchmarks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(80)  NOT NULL,
    ticker      VARCHAR(16)  NOT NULL UNIQUE,
    category    VARCHAR(16)  NOT NULL,

    CONSTRAINT chk_benchmarks_category CHECK (
        category IN ('BROAD', 'SECTOR', 'CRYPTO', 'BOND'))
);
