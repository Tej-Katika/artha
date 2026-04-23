# Artha — Project State

_Last updated: 2026-04-23._

Single source of truth for what the Artha project is, what's been done,
and what's still open. Read this before resuming work.

---

## 1. What this project is

**Artha** is a domain-ontology-driven agentic framework for LLM-based
personal finance reasoning. It is the rebranded version of the original
"FinWise" codebase (Java packages remain `com.finwise.*` — see
"Naming conventions" below).

- **Backend**: Java 21, Spring Boot 3.2.x, Spring Data JPA, HikariCP.
- **Database**: PostgreSQL 15 (local), `postgres` database.
- **LLM**: Anthropic Claude API, agent model
  `claude-sonnet-4-6`, judge model `claude-opus-4-7`.
- **Frontend**: React 18 / TypeScript (not used for evaluation).
- **Eval harness**: Python 3.13, `anthropic`, `requests`.
- **Paper target**: arXiv preprint -> IEEE Big Data 2026.

## 2. Naming conventions

The framework was renamed from "FinWise" to "Artha" for publication.

- Java packages: `com.finwise.*` (unchanged; renaming would require a
  destructive rebuild and is not worth the risk). README, `pom.xml`,
  and the Spring application name are branded as Artha.
- Python scripts: `finwise_users_v2.json` (unchanged; it's data).
- API endpoints: `/api/agent/chat`, `/api/enrichment/...` (unchanged).
- Database: `postgres` (name unchanged; tables are logically the Artha
  ontology).

## 3. Current system state

### Architecture

- **Raw layer**: `transactions`, `bank_accounts`, `users` (immutable).
- **Ontology layer (9 core types)**:
  Transaction, TransactionEnrichment, MerchantProfile, MerchantType,
  SpendingCategory, ClassificationRule, RecurringBill, Budget,
  FinancialGoal.
- **Enrichment pipeline**:
  - `OntologyEnrichmentService` — rule-based classification +
    metadata fallback (three-tier: RULES -> METADATA -> FALLBACK).
  - `StatisticalAnomalyDetector` — per-merchant z-score (> 2.5).
  - `SubscriptionDetector` — frequency + interval heuristic
    (>=4 occurrences, 5-40 day interval for MONTHLY).
- **Agent layer**: 15 typed tools, Claude Sonnet 4.6 orchestration,
  max 8 tool iterations per query.

### Key configuration

In `application.yml` and driven by env vars:

| Property | Default | Purpose |
|---|---|---|
| `ARTHA_EVAL_REFERENCE_DATE` | (unset) | Pin "today" to an ISO date for reproducible benchmarks (used: `2024-12-31`). Unset in prod. |
| `ARTHA_ONTOLOGY_TOOLS_ENABLED` | `true` | Ablation flag; false bypasses the ontology path in 4 tools. |
| `ANTHROPIC_API_KEY` | required | Agent + judge both use it. |

### Data coverage (eval users only)

10 archetype seed users (`user_ids[0]` per archetype; one per archetype):

- Transactions: 1,801 across 10 users (range 124-292 each)
- Enrichments: 100% (all 1,801 assigned)
- Source split: **~40% RULES, ~60% METADATA fallback**
- Anomalies: 50 flagged across 10 users (concentrated in 2 archetypes)
- Recurring bills: 97 detected (avg 10/user)
- Goals: 2 per user (Emergency Fund, Retirement), seeded
- Budgets: 3 per user (FOOD_AND_DRINK, SHOPPING, ENTERTAINMENT),
  seeded

## 4. Evaluation results (paper-ready)

60-query benchmark = 6 categories x 10 archetypes. LLM-as-judge uses
Claude Opus 4.7; pass = all 4 dimensions >= 3/5.

### Overall (Table V)

| Cond. | Enriched | Tools | Pass % | Avg/5 | Delta vs A |
|---|---|---|---|---|---|
| **A — Full** | yes | yes | **75.0%** | 3.67 | --- |
| B — Tools OFF | yes | no | 50.0% | 2.97 | -25.0 pp |
| C — Enrichment OFF | no | yes | 70.0% | 3.56 | -5.0 pp |
| D — Both OFF (raw baseline) | no | no | 48.3% | 2.86 | **-26.7 pp** |

### Per-category pass rate (Table VI)

| Category | A | B | C | D |
|---|---|---|---|---|
| spending_summary | 100% | 70% | 80% | 60% |
| financial_health | 80% | 70% | 70% | 80% |
| goal_tracking | 60% | 60% | 70% | 60% |
| anomaly_detection | 30% | 0% | 0% | 0% |
| subscription_audit | 90% | 0% | 100% | 0% |
| behavioral_analysis | 90% | 100% | 100% | 90% |

Machine-readable source: `eval_results/summary_{A,B,C,D}.json`.

## 5. Work done in this session

Chronological log of the fixes that got us from a broken baseline to
paper-ready data.

1. **Eval harness fixes**
   - Loader: `finwise_users_v2.json` is nested
     (`data["archetypes"][<name>]`) not flat; patched `load_user_ids`.
   - Judge model: `claude-sonnet-4-5-20250514` (404) ->
     `claude-opus-4-7`.
   - Judge params: removed `temperature=0` (Opus 4.7 rejects it).
2. **Reference-date override**
   - Added `ReferenceDateProvider` bean, injected into all 13 time-
     dependent tools and ontology services.
   - Anchored eval at `2024-12-31` so the 2024-era data intersects
     the tools' "last 30/90/180 days" query windows.
3. **Multi-source enrichment**
   - Modified `OntologyEnrichmentService.resolveCategory()` to fall
     back to `transaction.metadata['category']` when rules miss.
   - Source now labeled `RULES` / `METADATA` / `FALLBACK`.
   - Added 15 merchant rules for top-volume merchants
     (McDonald's, T-Mobile, Verizon, Lyft, DoorDash, Kroger, Target,
     Walgreens, CVS, Costco, Trader Joe's, Olive Garden,
     Cheesecake Factory, Apple, StubHub) on top of the original 8.
   - Result: 40.4% RULES + 59.6% METADATA coverage
     (vs. 16% before).
4. **Subscription detector tuning**
   - Disabled the strict amount-variance filter (generator noise was
     too high).
   - Widened monthly interval from `[25, 35]` to `[5, 40]` days.
   - Raised `MIN_OCCURRENCES` from 3 to 4.
   - Disclosed in the paper as post-hoc tuning for the synthetic
     dataset.
5. **Force re-enrich endpoint** + `@Autowired @Lazy self` + flush
   after delete fixed JPA unique-constraint collision.
6. **Anomaly tool rich empty-state** — returns scan counts, distinct
   merchants, top merchants, method, threshold even when 0 anomalies.
7. **Ontology-bypass feature flag** (`FeatureFlags` +
   `artha.ontology.tools-enabled`) for Conditions B and D.
8. **Goals and budgets seeded** uniformly via SQL (evaluation-setup
   data, not outputs of Artha; clearly disclosed).
9. **Eval harness rate-limit pacing** — 2 s between queries to avoid
   per-minute tool-use burst limits on Anthropic API.

## 6. Known limitations (disclosed in paper)

1. **Single seed user per archetype (n=1)**. Expand to n >= 5 in
   future work.
2. **Synthetic dataset only**. No real bank data validated.
3. **Post-hoc detector tuning** (subscription interval window).
4. **Reference-date substitution** at 2024-12-31 (disabled in prod).
5. **Same-provider LLM judge** (Sonnet 4.6 agent vs Opus 4.7 judge,
   both Anthropic).
6. **No Cohen's kappa** computed — rubric is released
   (`SCORING_RUBRIC.md`) but human annotation not performed.
7. **Seeded goals / budgets** (not outputs of the system).
8. **23 rules only** (production would scale to hundreds/thousands).

## 7. Key files

### Code (backend)

- `src/main/java/com/finwise/agent/core/ReferenceDateProvider.java`
- `src/main/java/com/finwise/agent/core/FeatureFlags.java`
- `src/main/java/com/finwise/agent/ontology/OntologyEnrichmentService.java`
- `src/main/java/com/finwise/agent/ontology/SubscriptionDetector.java`
- `src/main/java/com/finwise/agent/ontology/StatisticalAnomalyDetector.java`
- `src/main/java/com/finwise/agent/tools/*.java` (15 tools; 4 have
  ablation branches)
- `src/main/java/com/finwise/agent/api/EnrichmentController.java`
  (`/all?force=true`, `/subscriptions?force=true`)
- `src/main/resources/application.yml`

### Code (eval and data setup)

- `artha_eval.py` — 60-query harness with 2 s inter-query pacing.
- `eval_setup/setup_eval_data.sql` — rules, categories, goals,
  budgets idempotent setup.
- `eval_setup/phase6_subscriptions.sql` — is_recurring flagging +
  recurring_bills seed (legacy; deprecated by `phase7_broaden_subs.sql`).
- `eval_setup/phase7_broaden_subs.sql` — frequency-based recurring
  bill seeding (legacy; superseded by Java `SubscriptionDetector`).
- `eval_setup/clear_eval_enrichment.sql` — Conditions C and D.
- `eval_setup/run_java_enrichment.py` — driver that force-re-enriches
  the 10 eval users through the real Java pipeline.

### Outputs

- `eval_results/summary_{A,B,C,D}.json`
- `eval_results/run_{A,B,C,D}_*.json` and `.txt`
- `eval_results/ablation_summary.txt`

### Paper artifacts

- `artha.tex` — current paper (under rewrite to adopt the better
  structure of `artha_2.pdf` with measured numbers).
- `artha_2.pdf` — earlier draft with **projected numbers** (do NOT
  submit as-is; numbers do not match actual measurements).
- `arxiv_submission/` — packaged upload (artha.tex, tarball, zip,
  abstract, metadata, SUBMIT.md guide).
- `PAPER_README.md`, `SCORING_RUBRIC.md`, `CLAUDE.md`.

## 8. How to reproduce

### Preflight

```powershell
# PostgreSQL with the artha/finwise tables populated
Test-NetConnection -ComputerName localhost -Port 5432

# Spring Boot up with reference-date override
$env:ARTHA_EVAL_REFERENCE_DATE = "2024-12-31"
$env:ANTHROPIC_API_KEY = "sk-ant-..."
mvn spring-boot:run      # expect: "ReferenceDateProvider: eval override active"

# Python deps
py -3 -m pip install anthropic requests
```

### Run Condition A (full system)

```powershell
$env:ARTHA_ONTOLOGY_TOOLS_ENABLED = "true"   # default
# (restart Spring Boot if changed)
py -3 artha_eval.py --condition A --api-url http://localhost:8081/api/agent/chat
```

### Run Conditions C or D (enrichment cleared)

```powershell
# Clear ontology data for the 10 eval users
psql -h localhost -U postgres -d postgres -f eval_setup/clear_eval_enrichment.sql

py -3 artha_eval.py --condition C   # or D
```

### Run Conditions B or D (ontology tools bypassed)

```powershell
# Flip the flag and restart Spring Boot
$env:ARTHA_ONTOLOGY_TOOLS_ENABLED = "false"
mvn spring-boot:run                # expect: "FeatureFlags: ontology-tools DISABLED"

py -3 artha_eval.py --condition B  # or D (clear enrichment first)
```

### Restore full Condition A state

```powershell
# Restart with tools-enabled=true (unset the flag or set it explicitly)
$env:ARTHA_ONTOLOGY_TOOLS_ENABLED = "true"
mvn spring-boot:run

# Re-enrich via the real Java pipeline
py -3 eval_setup/run_java_enrichment.py
```

## 9. Open tasks / deferred work

Ordered by priority for arXiv / IEEE submission:

1. **Finalize the paper** (rewrite `artha.tex` to adopt the stronger
   structure of `artha_2.pdf` with real numbers and honest
   disclosures).
2. **Cohen's kappa on a 10-12 response sample** (2 humans scoring via
   `SCORING_RUBRIC.md`, compute with `compute_kappa.py`).
3. **Cross-family LLM judge** (GPT-4.1 or Gemini as second judge on
   the same 60 Condition A responses).
4. **Expand eval to n >= 5 users per archetype** (requires budget).
5. **Replace Plaid integration** and validate on real bank data under
   appropriate privacy controls.

## 10. Contact

Tejas Katika — tejashwar1029@gmail.com — University of North Texas.
