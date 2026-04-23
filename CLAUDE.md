# CLAUDE.md — Artha Evaluation Pipeline
# ======================================
# This file gives Claude Code full context to run the experimental
# evaluation for the Artha research paper (arXiv submission).
#
# Project: Artha — A Domain Ontology-Driven Agentic Framework
#          for LLM-Based Personal Finance Reasoning
# Author:  Tejas Katika, University of North Texas
# Target:  arXiv preprint → IEEE Big Data 2026

# ─── PROJECT OVERVIEW ────────────────────────────────────────────

## What is Artha?

Artha (formerly FinWise) is a personal finance AI agent built with:
- **Backend:** Java 21 / Spring Boot 3.2.x / Spring Data JPA
- **Database:** PostgreSQL 15 (local, no Docker needed)
- **LLM:** Anthropic Claude API (claude-sonnet-4-5-20251001) via tool-calling
- **Frontend:** React 18 / TypeScript (not needed for eval)
- **Eval stack:** Python 3.11+ with `anthropic` and `requests` packages

The core innovation: a **domain ontology layer** (9 object types, 7 relationships)
that enriches raw bank transactions into typed financial objects BEFORE the LLM
reasons over them. A 13-tool agentic layer queries these ontology objects rather
than raw SQL.

## What needs to happen

We need to run the evaluation pipeline to generate **real experimental results**
for the arXiv paper. The paper currently has projected numbers that must be
replaced with actual observed results. Specifically:

1. Run 60 benchmark queries (6 categories × 10 archetypes) under **Condition A**
   (full system with enrichment + ontology tools)
2. Run the same 60 queries under **Conditions B, C, D** (ablation study)
3. Collect results into `eval_results/` directory
4. The results JSONs will be used to update the paper's tables

# ─── CRITICAL FILES AND PATHS ────────────────────────────────────

## Project structure (Windows paths)
```
C:\Users\tejas\Projects\finwise-agent\
├── src\main\java\com\finwise\          ← Java backend source
│   ├── agent\
│   │   ├── AgentOrchestrator.java      ← Tool-calling loop
│   │   └── tools\                      ← 13 tool implementations
│   │       ├── GetSpendingSummaryToolImpl.java
│   │       ├── GetBudgetStatusToolImpl.java
│   │       ├── GetAnomaliesToolImpl.java
│   │       ├── GetRecurringBillsToolImpl.java
│   │       ├── GetFinancialHealthToolImpl.java
│   │       ├── GetGoalProgressToolImpl.java
│   │       └── ...
│   └── enrichment\
│       └── EnrichmentService.java      ← Transaction enrichment engine
├── src\main\resources\
│   └── application.yml                 ← DB config, API key
├── pom.xml                             ← Maven build file
├── artha_eval.py                       ← Evaluation harness (copy from kit)
├── compute_kappa.py                    ← Inter-rater reliability
├── update_paper.py                     ← Auto-update paper with results
├── SCORING_RUBRIC.md                   ← For human annotators
├── generate_finwise_data_v2.py         ← Dataset generator (already run)
├── finwise_users_v2.json               ← User ID map from generator
└── CLAUDE.md                           ← THIS FILE
```

## Database
- **Host:** localhost:5432
- **Database name:** Check `application.yml` for the exact name
  (likely `finwise` or `artha`)
- **Key tables:**
  - `users` — 500 users with UUID PKs, archetype field
  - `transactions` — ~286,904 raw transaction records
  - `accounts` — bank accounts linked to users
  - `transaction_enrichments` — enriched category/merchant data
  - `spending_categories` — 19 category taxonomy
  - `merchant_rules` — 847 merchant classification rules
  - `budgets` — per-user budget limits
  - `financial_goals` — savings goals
  - `recurring_bills` — detected subscriptions

## API endpoint
- **Base URL:** http://localhost:8080
- **Agent chat:** POST http://localhost:8080/api/agent/chat
- **Request body:** `{"message": "...", "userId": "UUID-here"}`
- **Response:** JSON with `response` field containing the agent's answer

## User IDs
The file `finwise_users_v2.json` contains the mapping of user IDs to
archetypes. The eval harness reads this file to select one user per
archetype for benchmarking. If this file doesn't exist, query the DB:
```sql
SELECT id, name, archetype FROM users ORDER BY archetype LIMIT 50;
```

# ─── EVALUATION PIPELINE ────────────────────────────────────────

## Step 0: Prerequisites check

Before running anything, verify:

```powershell
# 1. Spring Boot app is running
curl http://localhost:8080/actuator/health

# 2. Database has data
# Connect to PostgreSQL and run:
# SELECT COUNT(*) FROM users;          -- expect 500
# SELECT COUNT(*) FROM transactions;   -- expect ~286,904
# SELECT COUNT(*) FROM transaction_enrichments;  -- expect > 0

# 3. Python packages installed
pip install anthropic requests

# 4. API key is set
echo $env:ANTHROPIC_API_KEY  # should not be empty

# 5. User map exists
Test-Path finwise_users_v2.json
```

If the Spring Boot app is NOT running:
```powershell
mvn spring-boot:run
# OR if using IntelliJ: Run the main Application class
```

If the database is empty (no users):
```powershell
python generate_finwise_data_v2.py --count 50 --clear --save-map
# Then trigger enrichment for all users via the API:
# POST http://localhost:8080/api/enrichment/user/{userId}/all
```

## Step 1: Run Condition A (Full System)

This is the most important run. The full Artha system with:
- Enrichment engine: ON (transaction_enrichments table populated)
- Ontology tools: ON (tools query enriched ontology tables)

```powershell
python artha_eval.py --api-url http://localhost:8080/api/agent/chat --condition A
```

Expected runtime: 2-3 hours (60 queries × ~2 min each including judge calls)
Expected cost: ~$10-15 in Anthropic API credits

Output files:
- `eval_results/run_A_YYYYMMDD_HHMMSS.json` — raw results
- `eval_results/run_A_YYYYMMDD_HHMMSS.txt` — human-readable report
- `eval_results/summary_A.json` — paper-ready numbers

### Quick test first (recommended)
```powershell
python artha_eval.py --quick --condition A
```
This runs only 6 queries (1 per category) to verify everything works.

## Step 2: Run Ablation Conditions B, C, D

### Condition B: Enrichment ON, Ontology Tools OFF

The enrichment data stays in the database, but the tool implementations
are modified to query `transactions.raw_category` directly instead of
going through the `transaction_enrichments` join tables.

**How to implement Condition B:**

Option 1 (feature flag in application.yml):
```yaml
artha:
  use-ontology-tools: false  # tools query raw tables
  enrichment-enabled: true   # enrichment data exists
```

Option 2 (modify tool SQL directly):
In each tool implementation (e.g., GetBudgetStatusToolImpl.java),
replace the ontology query:
```sql
-- ONTOLOGY QUERY (Condition A):
SELECT sc.name, ... FROM budgets b
JOIN spending_categories sc ON b.category_id = sc.id
LEFT JOIN transaction_enrichments te ON te.category_id = sc.id
LEFT JOIN transactions t ON te.transaction_id = t.id ...

-- RAW QUERY (Condition B):
SELECT t.raw_category AS category, SUM(ABS(t.amount)) AS spent
FROM transactions t
WHERE t.user_id = :userId
  AND t.transaction_date >= date_trunc('month', CURRENT_DATE)
GROUP BY t.raw_category
ORDER BY spent DESC;
```

Option 3 (simplest — create a separate Spring profile):
```yaml
# application-conditionB.yml
spring:
  profiles: conditionB
```
Then run with: `mvn spring-boot:run -Dspring-boot.run.profiles=conditionB`

After modifying, restart the app and run:
```powershell
python artha_eval.py --condition B
```

### Condition C: Enrichment OFF, Ontology Tools ON

The tool implementations use the ontology schema (budgets, spending_categories,
etc.) but the enrichment engine is disabled — meaning transaction_enrichments
has NULL category_id and merchant_type for all records.

**How to implement Condition C:**
```sql
-- Temporarily clear enrichment data:
TRUNCATE TABLE transaction_enrichments;
-- Keep the ontology tables (budgets, spending_categories, etc.) intact
```
Then restart the app (tools still query ontology tables, but find no matches):
```powershell
python artha_eval.py --condition C
```

**IMPORTANT:** After running Condition C, re-run the enrichment to restore data:
```sql
-- Re-populate by calling the enrichment API for each user
-- OR restore from a backup
```

### Condition D: Raw Baseline (both OFF)

Neither enrichment data nor ontology-aware tools. The agent gets only
raw transaction records with raw_category fields.

**How to implement Condition D:**
1. Clear enrichment data (like Condition C)
2. Switch tools to raw queries (like Condition B)
3. Restart and run:
```powershell
python artha_eval.py --condition D
```

### Interactive ablation mode

Alternatively, run all conditions interactively:
```powershell
python artha_eval.py --ablation
```
This will pause between conditions and prompt you to reconfigure the app.

## Step 3: Verify Results

After all 4 conditions are run, you should have:
```
eval_results/
├── summary_A.json      ← Full system results
├── summary_B.json      ← No ontology tools
├── summary_C.json      ← No enrichment
├── summary_D.json      ← Raw baseline
├── run_A_*.json        ← Raw run data
├── run_A_*.txt         ← Human-readable report
├── run_B_*.json
├── run_B_*.txt
├── run_C_*.json
├── run_C_*.txt
├── run_D_*.json
├── run_D_*.txt
└── ablation_combined.json  ← If using --ablation mode
```

Print the summary:
```powershell
python -c "
import json
for c in 'ABCD':
    try:
        with open(f'eval_results/summary_{c}.json') as f:
            s = json.load(f)
        print(f'Condition {c}: {s[\"overall_pass_rate\"]}% pass, {s[\"overall_avg_score\"]}/5 avg')
    except FileNotFoundError:
        print(f'Condition {c}: NOT RUN')
"
```

## Step 4: Human Evaluation (Manual — not automated)

This step requires two humans, not Claude Code.

1. Open `eval_results/run_A_*.txt` — the human-readable report
2. Pick 6 agent responses (1 per category, from hard archetypes:
   paycheck_to_paycheck, gig_worker, recent_grad, overspender,
   high_earner, high_debt)
3. Print SCORING_RUBRIC.md and give to 2 people
4. Have each person independently score the 6 responses
5. Enter scores into compute_kappa.py
6. Run: `python compute_kappa.py`

# ─── KNOWN ISSUES AND FIXES ─────────────────────────────────────

## API response format
The eval harness tries multiple response shapes:
- `body.get("response", ...)` — if your API returns `{"response": "..."}`
- `body.get("message", ...)` — if it returns `{"message": "..."}`
- Falls back to `str(body)` — if the API returns the text directly

If the agent responses come back empty or garbled, check the actual
API response format:
```powershell
$body = '{"message": "How much did I spend?", "userId": "YOUR-USER-ID"}'
Invoke-RestMethod -Uri "http://localhost:8080/api/agent/chat" -Method Post -Body $body -ContentType "application/json"
```
Then update the `call_agent()` function in artha_eval.py to match.

## Anomaly detection window
Known issue from earlier testing: anomaly detection uses a 90-day
baseline window but eval queries assume 30-day. If anomaly scores
are unexpectedly low, check GetAnomaliesToolImpl.java and adjust
the baseline window.

## Enrichment not triggered
If transaction_enrichments is empty after data generation, enrichment
may not have been triggered. Call the enrichment API:
```
POST http://localhost:8080/api/enrichment/user/{userId}/all
```
for each user, or write a batch script that iterates through all 500 users.

## Out of memory
500 users × ~573 transactions each = 286,904 rows. If the Spring Boot
app runs out of memory during large queries, increase heap:
```powershell
$env:JAVA_OPTS = "-Xmx2g"
mvn spring-boot:run
```

# ─── WHAT THE PAPER NEEDS ────────────────────────────────────────

After all runs complete, the paper needs these specific numbers:

## Table 4 (Results — Condition A only):
| Category | Pass Rate | Avg Score | Lowest Archetype |
|----------|-----------|-----------|------------------|
| Spending summary | from summary_A.json | | |
| Financial health | | | |
| Goal tracking | | | |
| Anomaly detection | | | |
| Subscription audit | | | |
| Behavioral analysis | | | |
| **Overall** | **overall_pass_rate** | **overall_avg_score** | |

## Table 5 (Ablation — overall pass rates):
| Condition | Enriched | Ontology Tools | Pass Rate | Delta vs D |
|-----------|----------|----------------|-----------|------------|
| A | ✓ | ✓ | from summary_A | computed |
| B | ✓ | | from summary_B | computed |
| C | | ✓ | from summary_C | computed |
| D | | | from summary_D | baseline |

## Table 6 (Per-category ablation):
Build from category_summary in each summary_X.json

## Cohen's kappa:
From compute_kappa.py output

## Qualitative example:
Pick one response from the paycheck_to_paycheck archetype in run_A_*.txt
that shows the system surfacing overdraft fees, payday loans, and
recurring fast-food charges. Quote the specific dollar amounts in
the paper.

# ─── FRAMEWORK RENAME NOTES ─────────────────────────────────────

The framework was originally called "FinWise" and has been renamed
to "Artha" for the paper. In the codebase:
- Java packages are still `com.finwise.*`
- Python scripts reference `finwise_users_v2.json`
- The API endpoints are at `/api/agent/chat` (unchanged)
- The database may be named `finwise` or `artha`

For the eval harness, these internal names don't matter — it only
needs the API URL and user IDs. The rename can be done after the
eval is complete, before publishing the GitHub repo.

# ─── TIMELINE ────────────────────────────────────────────────────

Priority order:
1. Quick test (--quick) to verify API works: 10 min
2. Full Condition A run: 2-3 hours
3. Conditions B, C, D: 2-3 hours each (can run overnight)
4. Human evaluation: 1 hour (need 2 humans)
5. Update paper with real numbers: 30 min
6. Create 2 diagrams in draw.io: 90 min
7. Submit to arXiv

Total: ~2-3 days of focused work, mostly waiting for API calls.
