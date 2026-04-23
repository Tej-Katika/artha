-- ═══════════════════════════════════════════════════════════════
--  FinWise AI Agent — Migration V2
--  Document Storage: W2 Forms + Bank Statements
--
--  Design principles:
--   1. W2 data is fully normalized — each IRS box is its own column.
--      The agent queries box values directly; JSONB would require
--      parsing on every read and break index usage.
--
--   2. Bank statement transactions merge into the existing
--      `transactions` table (already in V1). Added `source` and
--      `source_account_id` columns to distinguish Plaid vs PDF.
--
--   3. `document_uploads` is an immutable audit log — one row per
--      file uploaded, never updated. Used for dedup, debug, and UI.
--
--   4. `statement_imports` stores period metadata per statement PDF
--      so the agent can answer "what data do I have?" without
--      scanning millions of transactions.
-- ═══════════════════════════════════════════════════════════════

-- ── 1. Extend transactions table with source tracking ──────────
--
-- Existing transactions came from Plaid. We need to distinguish:
--   PLAID    = live bank connection via Plaid API
--   STATEMENT = parsed from an uploaded bank statement PDF
--   MANUAL   = user-entered (future)

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS source         VARCHAR(20) DEFAULT 'PLAID'
                                            CHECK (source IN ('PLAID', 'STATEMENT', 'MANUAL')),
    ADD COLUMN IF NOT EXISTS source_import_id UUID;  -- FK to statement_imports (added below)

COMMENT ON COLUMN transactions.source IS
    'Origin of this transaction: PLAID=live connection, STATEMENT=PDF upload, MANUAL=user-entered';

CREATE INDEX IF NOT EXISTS idx_transactions_source
    ON transactions(user_id, source);


-- ── 2. Document Uploads (audit log) ───────────────────────────
--
-- Immutable record of every file ever uploaded.
-- Never deleted — used for dedup prevention and parse debugging.

CREATE TABLE document_uploads (
    upload_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,

    -- File identity
    filename        VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    content_type    VARCHAR(100),               -- application/pdf

    -- What kind of document
    document_type   VARCHAR(20) NOT NULL
                    CHECK (document_type IN ('W2', 'BANK_STATEMENT', 'TAX_1099', 'OTHER')),

    -- Processing result
    parse_status    VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (parse_status IN ('PENDING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    parse_confidence VARCHAR(10)
                    CHECK (parse_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    parse_error     TEXT,                       -- Error message if FAILED

    -- Links to the parsed records (null if parsing failed)
    w2_record_id    UUID,                       -- Set if document_type = W2
    statement_import_id UUID,                  -- Set if document_type = BANK_STATEMENT

    -- Dedup: SHA-256 hash of file contents
    -- Prevents re-processing the exact same file
    content_hash    VARCHAR(64),

    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_uploads_user_id      ON document_uploads(user_id);
CREATE INDEX idx_uploads_type         ON document_uploads(user_id, document_type);
CREATE INDEX idx_uploads_status       ON document_uploads(user_id, parse_status);
CREATE UNIQUE INDEX idx_uploads_hash  ON document_uploads(user_id, content_hash)
    WHERE content_hash IS NOT NULL;             -- One row per unique file per user

COMMENT ON TABLE document_uploads IS
    'Immutable audit log of all uploaded documents. Used for dedup, debugging, and UI display.';


-- ── 3. W2 Records ─────────────────────────────────────────────
--
-- One row per W2 form per user per tax year.
-- All IRS boxes stored as individual columns for direct SQL queries.
-- The agent tool reads these columns; no JSON parsing required.

CREATE TABLE w2_records (
    w2_id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    upload_id       UUID REFERENCES document_uploads(upload_id) ON DELETE SET NULL,

    -- Which year this W2 covers
    tax_year        INTEGER NOT NULL
                    CHECK (tax_year BETWEEN 2000 AND 2099),

    -- Employer
    employer_name   VARCHAR(500),
    employer_ein    VARCHAR(20),                -- XX-XXXXXXX format

    -- ── Core Wage Boxes ─────────────────────────────────────
    box1_wages              DECIMAL(15,2),      -- Wages, tips, other compensation
    box2_federal_tax        DECIMAL(15,2),      -- Federal income tax withheld
    box3_ss_wages           DECIMAL(15,2),      -- Social security wages
    box4_ss_tax             DECIMAL(15,2),      -- Social security tax withheld
    box5_medicare_wages     DECIMAL(15,2),      -- Medicare wages and tips
    box6_medicare_tax       DECIMAL(15,2),      -- Medicare tax withheld

    -- ── Retirement (Box 12 codes) ────────────────────────────
    -- Each lettered code maps to a specific benefit type
    box12_code_d    DECIMAL(15,2),              -- Traditional 401(k)
    box12_code_aa   DECIMAL(15,2),              -- Roth 401(k)
    box12_code_e    DECIMAL(15,2),              -- 403(b)
    box12_code_s    DECIMAL(15,2),              -- SIMPLE 401(k)
    box12_code_w    DECIMAL(15,2),              -- HSA employer contributions
    box12_code_dd   DECIMAL(15,2),              -- Employer-sponsored health coverage cost
    box12_code_bb   DECIMAL(15,2),              -- Roth 403(b)

    -- ── State Tax (Boxes 15–17) ──────────────────────────────
    box15_state             VARCHAR(2),         -- Two-letter state code (e.g. 'TX', 'CA')
    box16_state_wages       DECIMAL(15,2),      -- State wages
    box17_state_tax         DECIMAL(15,2),      -- State income tax withheld

    -- ── Parsing metadata ─────────────────────────────────────
    parse_confidence        VARCHAR(10)
                            CHECK (parse_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    raw_extracted_text      TEXT,               -- Full PDF text for debugging/re-parsing
    payroll_provider        VARCHAR(100),       -- Detected: 'ADP', 'Paychex', 'Workday', etc.

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- One W2 per employer per tax year (prevent duplicates)
    CONSTRAINT uq_w2_user_year_employer
        UNIQUE (user_id, tax_year, employer_ein)
);

CREATE INDEX idx_w2_user_id       ON w2_records(user_id);
CREATE INDEX idx_w2_tax_year      ON w2_records(user_id, tax_year DESC);

COMMENT ON TABLE w2_records IS
    'Parsed W2 tax form data. Each IRS box is a separate column for direct querying by the agent.';

COMMENT ON COLUMN w2_records.box1_wages IS
    'IRS Box 1: Total wages, tips, and other compensation. The primary income figure.';
COMMENT ON COLUMN w2_records.box12_code_d IS
    'IRS Box 12 Code D: Traditional 401(k) elective deferrals. Max $23,500 in 2025.';
COMMENT ON COLUMN w2_records.box12_code_w IS
    'IRS Box 12 Code W: Employer + employee HSA contributions. Max $4,300 single / $8,550 family in 2025.';


-- ── 4. Statement Imports (metadata per PDF) ────────────────────
--
-- One row per bank statement PDF successfully parsed.
-- Stores period coverage and summary stats — not the transactions themselves.
-- Transactions are in the `transactions` table with source='STATEMENT'.

CREATE TABLE statement_imports (
    import_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    upload_id       UUID REFERENCES document_uploads(upload_id) ON DELETE SET NULL,

    -- Bank and account identification
    bank_name       VARCHAR(100),               -- 'CHASE', 'BANK_OF_AMERICA', 'WELLS_FARGO', etc.
    account_type    VARCHAR(50),                -- 'CHECKING', 'SAVINGS', 'CREDIT_CARD'
    account_last4   VARCHAR(4),                 -- Last 4 digits of account number (if parseable)

    -- Statement coverage period
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,

    -- What was extracted
    transaction_count   INTEGER NOT NULL DEFAULT 0,
    total_debits        DECIMAL(15,2),          -- Sum of all outgoing transactions
    total_credits       DECIMAL(15,2),          -- Sum of all incoming transactions
    opening_balance     DECIMAL(15,2),          -- Balance at start of period (if in PDF)
    closing_balance     DECIMAL(15,2),          -- Balance at end of period (if in PDF)

    parse_confidence    VARCHAR(10)
                        CHECK (parse_confidence IN ('HIGH', 'MEDIUM', 'LOW')),

    imported_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Prevent importing same period twice from same bank
    CONSTRAINT uq_statement_user_bank_period
        UNIQUE (user_id, bank_name, period_start, period_end)
);

CREATE INDEX idx_statement_user_id  ON statement_imports(user_id);
CREATE INDEX idx_statement_period   ON statement_imports(user_id, period_start DESC);
CREATE INDEX idx_statement_bank     ON statement_imports(user_id, bank_name);

COMMENT ON TABLE statement_imports IS
    'Metadata per uploaded bank statement PDF. Actual transactions stored in transactions table.';


-- ── 5. Add FK from transactions to statement_imports ──────────
--
-- Now that statement_imports exists, we can add the FK constraint
-- that was defined as UUID only in step 1.

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_statement_import
    FOREIGN KEY (source_import_id) REFERENCES statement_imports(import_id)
    ON DELETE SET NULL;

COMMENT ON COLUMN transactions.source_import_id IS
    'Links to statement_imports.import_id when source=STATEMENT. NULL for Plaid transactions.';


-- ── 6. User Data Summary View ──────────────────────────────────
--
-- Convenient view the agent uses to check what data is available
-- before deciding which tools to call.
-- "Does this user have W2 data? Plaid? Statements?"

CREATE OR REPLACE VIEW user_data_summary AS
SELECT
    u.user_id,
    u.email,

    -- Plaid connection
    u.plaid_connected,
    (SELECT COUNT(*) FROM transactions t
     WHERE t.user_id = u.user_id AND t.source = 'PLAID') AS plaid_transaction_count,

    -- W2 data
    (SELECT COUNT(*) FROM w2_records w WHERE w.user_id = u.user_id) AS w2_count,
    (SELECT MAX(tax_year) FROM w2_records w WHERE w.user_id = u.user_id) AS latest_w2_year,
    (SELECT box1_wages FROM w2_records w
     WHERE w.user_id = u.user_id ORDER BY tax_year DESC LIMIT 1) AS latest_w2_wages,

    -- Statement data
    (SELECT COUNT(*) FROM statement_imports si WHERE si.user_id = u.user_id) AS statement_count,
    (SELECT COUNT(*) FROM transactions t
     WHERE t.user_id = u.user_id AND t.source = 'STATEMENT') AS statement_transaction_count,
    (SELECT MIN(period_start) FROM statement_imports si
     WHERE si.user_id = u.user_id) AS oldest_statement_date,
    (SELECT MAX(period_end) FROM statement_imports si
     WHERE si.user_id = u.user_id) AS newest_statement_date,

    -- Combined transaction coverage
    (SELECT MIN(date) FROM transactions t WHERE t.user_id = u.user_id) AS transactions_from,
    (SELECT MAX(date) FROM transactions t WHERE t.user_id = u.user_id) AS transactions_to,
    (SELECT COUNT(*) FROM transactions t WHERE t.user_id = u.user_id) AS total_transactions

FROM users u;

COMMENT ON VIEW user_data_summary IS
    'Quick summary of all data sources available per user. Used by the agent to check data availability.';


-- ── 7. Useful queries for the agent tools ─────────────────────
--
-- These aren't schema objects — they're documented here as
-- reference for the Spring Data JPA @Query annotations.
--
-- Find most recent W2:
--   SELECT * FROM w2_records WHERE user_id = ? ORDER BY tax_year DESC LIMIT 1
--
-- Get all transaction data coverage:
--   SELECT source, MIN(date) as from, MAX(date) as to, COUNT(*)
--   FROM transactions WHERE user_id = ? GROUP BY source
--
-- Check for duplicate statement upload:
--   SELECT * FROM statement_imports
--   WHERE user_id = ? AND bank_name = ? AND period_start = ? AND period_end = ?
--
-- Get spending by category across ALL sources (Plaid + statements):
--   SELECT category, SUM(amount) as total
--   FROM transactions
--   WHERE user_id = ? AND date BETWEEN ? AND ? AND amount > 0
--   GROUP BY category ORDER BY total DESC
