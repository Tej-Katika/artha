# FinWise Database Design

Complete reference for how PostgreSQL stores W2 forms, bank statements, and Plaid data.

---

## The Big Picture

```
Three data sources → Three ingestion paths → One unified query layer

W2 PDF ──────────────→ w2_records              (structured IRS box data)
                    ↘
                      document_uploads          (audit: every file ever uploaded)
Bank Statement PDF ──→ statement_imports        (metadata: bank, period, counts)
                    ↘ transactions (source=STATEMENT)  ←─┐
                                                          │  Same table.
Plaid Bank Link ─────→ transactions (source=PLAID)  ─────┘  Same queries.
```

The key design decision: **all transactions — whether from Plaid or a PDF — live in the same `transactions` table.** This means every agent tool (`getSpendingByCategory`, `getTransactions`, etc.) works identically regardless of how the data arrived. The `source` column distinguishes them, but tools don't need to care about it.

---

## Tables

### `transactions` (extended in V2)

Exists from V1. V2 adds two columns:

| Column | Type | Purpose |
|--------|------|---------|
| `source` | VARCHAR(20) | `PLAID` / `STATEMENT` / `MANUAL` |
| `source_import_id` | UUID | FK to `statement_imports` when `source=STATEMENT` |

**Why not separate tables for Plaid vs statement transactions?**

Because the agent asks questions like "how much did I spend on groceries last month?" — it doesn't care whether the data came from Plaid or a PDF. Keeping one table means one query, one index, one tool implementation. Separate tables would require UNION queries everywhere and complicate every tool.

---

### `w2_records`

One row per W2 form per user per tax year. Every IRS box is its own column.

```sql
w2_id           UUID PK
user_id         UUID → users
tax_year        INTEGER          -- 2024, 2025, etc.
employer_name   VARCHAR
employer_ein    VARCHAR          -- XX-XXXXXXX

-- Income
box1_wages      DECIMAL(15,2)   -- THE most important field
box2_federal_tax DECIMAL(15,2)  -- For effective tax rate

-- Social Security + Medicare (FICA)
box3_ss_wages   DECIMAL(15,2)
box4_ss_tax     DECIMAL(15,2)
box5_medicare_wages DECIMAL(15,2)
box6_medicare_tax   DECIMAL(15,2)

-- Retirement (Box 12 codes)
box12_code_d    DECIMAL(15,2)   -- Traditional 401(k)
box12_code_aa   DECIMAL(15,2)   -- Roth 401(k)
box12_code_e    DECIMAL(15,2)   -- 403(b)
box12_code_w    DECIMAL(15,2)   -- HSA employer contributions

-- State
box15_state     VARCHAR(2)       -- 'TX', 'CA', etc.
box16_state_wages   DECIMAL(15,2)
box17_state_tax     DECIMAL(15,2)
```

**Why individual columns instead of JSONB?**

Three reasons:
1. The `getIncomeAnalysis` agent tool reads `box1_wages`, `box2_federal_tax` directly. Column access is faster and simpler than JSON extraction.
2. PostgreSQL can index individual columns. `WHERE box1_wages > 100000` uses an index; `WHERE data->>'box1' > '100000'` does not.
3. The schema self-documents what data exists. JSONB hides structure.

**Duplicate prevention:**

```sql
CONSTRAINT uq_w2_user_year_employer UNIQUE (user_id, tax_year, employer_ein)
```

A user can't accidentally import the same W2 twice. If they have two jobs, they'll have two rows for the same year (different EINs) — which is correct.

---

### `document_uploads`

Immutable audit log. One row per file, never updated.

```sql
upload_id       UUID PK
user_id         UUID → users
filename        VARCHAR
file_size_bytes BIGINT
document_type   VARCHAR    -- W2 | BANK_STATEMENT | TAX_1099 | OTHER
parse_status    VARCHAR    -- PENDING | SUCCESS | PARTIAL | FAILED
parse_confidence VARCHAR   -- HIGH | MEDIUM | LOW
parse_error     TEXT       -- Error message if parsing failed
content_hash    VARCHAR(64) -- SHA-256 of file bytes (dedup)
uploaded_at     TIMESTAMPTZ
```

**Why track uploads separately from parsed data?**

- **Dedup:** `content_hash` prevents processing the exact same file twice. The user uploads the same PDF again → we check the hash, find a match, return the cached result.
- **Debugging:** Parse failed? We have the filename, file size, and error message without needing the original file.
- **UI:** "Show me what I've uploaded" — this table answers that query instantly without scanning w2_records or statement_imports.
- **Partial success:** A statement PDF might parse 47 of 50 transactions. `parse_status=PARTIAL` records this; the user knows something was missed.

---

### `statement_imports`

Metadata for each bank statement PDF. One row per file.

```sql
import_id       UUID PK
user_id         UUID → users
bank_name       VARCHAR    -- CHASE | BANK_OF_AMERICA | WELLS_FARGO | etc.
account_type    VARCHAR    -- CHECKING | SAVINGS | CREDIT_CARD
period_start    DATE       -- Statement start date
period_end      DATE       -- Statement end date
transaction_count INTEGER  -- How many transactions were extracted
total_debits    DECIMAL    -- Sum of all spending in this statement
total_credits   DECIMAL    -- Sum of all income/deposits
opening_balance DECIMAL    -- Balance at start (if found in PDF)
closing_balance DECIMAL    -- Balance at end (if found in PDF)
```

**Duplicate prevention:**

```sql
CONSTRAINT uq_statement_user_bank_period
    UNIQUE (user_id, bank_name, period_start, period_end)
```

Can't import the same Chase January statement twice.

**What the agent uses this for:**

When a user asks "what data do you have about me?" the agent calls a data-availability check that queries `statement_imports` — it can answer "I have your Chase checking statements from October 2024 through February 2026" without scanning every transaction row.

---

### `user_data_summary` (view)

A computed view the agent reads to know what's available before deciding which tools to call:

```sql
SELECT
    user_id,
    plaid_connected,
    plaid_transaction_count,
    w2_count,
    latest_w2_year,
    latest_w2_wages,
    statement_count,
    statement_transaction_count,
    oldest_statement_date,
    newest_statement_date,
    transactions_from,
    transactions_to,
    total_transactions
FROM user_data_summary
WHERE user_id = ?
```

This lets the agent give context-aware answers. If `w2_count = 0`, the agent proactively tells the user "upload your W2 and I can tell you your exact savings rate."

---

## How Flyway Runs The Migrations

Flyway reads migration files in version order at startup:

```
src/main/resources/db/migration/
  V1__initial_schema.sql     ← Runs first (always, on fresh DB)
  V2__document_storage.sql   ← Runs second
  V3__...sql                 ← Future migrations
```

Flyway tracks what's been applied in its own `flyway_schema_history` table. If V1 is already applied, it skips it and only runs new migrations. This means:

- **Fresh database:** Both V1 and V2 run on first startup
- **Existing database (V1 applied):** Only V2 runs
- **Up-to-date database:** Nothing runs — app starts immediately

You never manually apply SQL. Flyway does it automatically every time the Spring Boot app starts.

---

## Running It Locally

### Step 1: Start PostgreSQL with pgvector

```bash
docker compose up -d
```

This starts the `pgvector/pgvector:pg16` image which has the `vector` extension pre-installed. The `init.sql` script enables it on first run.

### Step 2: Verify extensions loaded

```bash
docker exec finwise-db psql -U finwise -d finwise -c "\dx"
```

Expected output:
```
   Name    | ...  | Description
-----------+------+--------------------------------
 uuid-ossp | ...  | generate universally unique identifiers
 vector    | ...  | vector data type and ivfflat access method
```

### Step 3: Start the app — Flyway runs automatically

```bash
./mvnw spring-boot:run
```

On first run you'll see in the logs:
```
Flyway Community Edition ... by Redgate
Database: jdbc:postgresql://localhost:5432/finwise (PostgreSQL 16)
Successfully validated 2 migrations
Migrating schema "public" to version "1 - initial schema"
Migrating schema "public" to version "2 - document storage"
Successfully applied 2 migrations to schema "public"
```

### Step 4: Explore the database (optional)

Connect with any PostgreSQL client (pgAdmin, TablePlus, DBeaver, or psql):

```
Host:     localhost
Port:     5432
Database: finwise
Username: finwise
Password: finwise
```

Or with psql directly:
```bash
docker exec -it finwise-db psql -U finwise -d finwise

# List all tables
\dt

# Describe w2_records
\d w2_records

# Check data summary view
SELECT * FROM user_data_summary;

# See what transactions are loaded
SELECT source, COUNT(*), MIN(date), MAX(date)
FROM transactions
GROUP BY source;
```

---

## Data Flow: W2 Upload

```
User uploads W2 PDF
         ↓
DocumentUploadController.uploadW2()
         ↓
  1. Validate: is it PDF? size < 10MB?
  2. Compute SHA-256 hash → check document_uploads for duplicates
  3. W2ParserService.parse() → extracts text via PDFBox
         ↓
  4. Regex patterns extract each IRS box value
  5. Confidence scoring: HIGH if box1 + box2 + year found
         ↓
  6. Save W2Record → w2_records table
  7. Save DocumentUpload (audit) → document_uploads table
         ↓
  8. Return: wages, tax rate, confidence, "capabilities unlocked" list
         ↓
Agent can now answer:
  - "What's my gross income?"     → getIncomeAnalysis → box1_wages
  - "What's my effective tax rate?" → box2 / box1
  - "Am I maxing my 401k?"        → box12_code_d vs $23,500 limit
  - "What's my take-home pay?"    → box1 - box2 - box4 - box6 - box12
```

## Data Flow: Bank Statement Upload

```
User uploads Chase January statement PDF
         ↓
DocumentUploadController.uploadStatement()
         ↓
  1. Validate: PDF, size < 25MB
  2. BankStatementParserService.parse()
         ↓
  3. Detect bank: scan first 500 chars for "JPMorgan Chase" → BankType.CHASE
  4. Extract statement period: "01/01/2026 through 01/31/2026"
  5. Check statement_imports: duplicate? → return cached result
         ↓
  6. Apply Chase-specific transaction parser (regex for MM/DD format)
  7. Rule-based categorization: "WHOLEFDS" → "Groceries"
         ↓
  8. Save to transactions (source='STATEMENT', source_import_id=<import>)
  9. Save StatementImport metadata
 10. Save DocumentUpload (audit)
         ↓
Agent can now answer everything getTransactions/getSpendingByCategory can answer
— using the same tools, same queries, same code as Plaid data
```

## Data Flow: Plaid Connection

```
User clicks "Connect Bank" in React frontend
         ↓
GET /api/plaid/link-token
  → plaidClient.linkTokenCreate() → returns link_token
         ↓
Frontend: initialize Plaid Link widget with link_token
User authenticates with Chase on Plaid's interface
Plaid calls onSuccess(public_token, metadata)
         ↓
POST /api/plaid/exchange-token { publicToken }
  → plaidClient.itemPublicTokenExchange() → access_token + item_id
  → Encrypt access_token with AES-256
  → Save to users.plaid_access_token (BYTEA)
         ↓
Async: PlaidService.syncTransactions(last 90 days)
  → Paginate through Plaid's /transactions/get (500 per page)
  → Normalize to Transaction entities (source='PLAID')
  → Upsert into transactions table
         ↓
Scheduler: re-sync every 6 hours (configured in application.yml)
```
