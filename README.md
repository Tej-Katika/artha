# Artha

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)
[![Anthropic](https://img.shields.io/badge/LLM-Claude-purple.svg)](https://www.anthropic.com/claude)

A domain-ontology-driven agentic framework for LLM-based personal-finance
reasoning over **banking** and **personal-investments** data.

Artha enriches raw financial data into typed ontology objects at
ingestion and exposes them to a Claude agent through three formal axes:

- **Actions** — typed, transactional, append-only-audited writes.
- **Provenance** — every derived fact is traceable to its sources and
  combiners with calibrated confidence.
- **Constraints** — soft and hard predicates evaluated on every agent
  response; violations trigger up to *K = 2* repair retries before the
  response is allowed to reach the user.

## Highlights

- **Two domains, one framework** — banking and investments share the
  same `core` runtime (orchestrator, tool dispatch, action executor,
  provenance combiner, constraint checker). Domain dispatch via a
  `?domain=banking|investments` query parameter.
- **18 ontology object types** — 9 banking (transactions, enrichments,
  merchant profiles/types, spending categories, budgets, recurring
  bills, financial goals, classification rules) and 9 investments
  (portfolios, positions, lots, trades, securities, dividends, fees,
  risk profiles, benchmarks).
- **Typed agent tools** — 21 tools surface ontology objects to the
  agent through a bounded tool-calling loop with a configurable model
  (defaults to Claude Haiku 4.5; configurable up to Sonnet/Opus).
- **Claim-driven constraint checking** — a regex extractor pulls
  factual claims from each candidate response (spending amounts,
  goal-progress assertions, merchant classes, date ranges); 8 banking
  constraints validate them against the ontology and trigger repair
  retries on violation. Every violation is logged in `violation_log`
  for telemetry.
- **Auditable writes** — every state-changing action writes one
  `action_audit` row regardless of outcome (SUCCESS, precondition
  failure, postcondition failure, rollback). Hoare-triple soundness
  is enforced by `ActionExecutor`.
- **Reproducible inference** — feature flags and reference-date
  overrides isolate ablation runs from real wall-clock drift.
- **Open synthetic data** — Python generators produce calibrated
  banking and investments datasets; investments prices come from
  real Yahoo Finance fetches via a Python ETL.

## Architecture

```
   ┌──────────────────────────────────────────────────────────────┐
   │             Banking inputs              Investment inputs    │
   │       (bank API / generator)        (Yahoo / generator)      │
   │                  │                              │            │
   │                  ▼                              ▼            │
   │         Enrichment Engine            Investments DataLoader  │
   │         ─ rule-based class.          ─ securities upsert     │
   │         ─ statistical anomaly        ─ daily prices upsert   │
   │         ─ recurring-bill detect.     ─ risk-free rate        │
   │                  │                              │            │
   │                  ▼                              ▼            │
   │       PostgreSQL ontology  ◄──────►  PostgreSQL ontology     │
   │       (banking, 9 types)             (investments, 9 types)  │
   │                  │                              │            │
   │                  └─────────────┬────────────────┘            │
   │                                ▼                             │
   │                       21 typed agent tools                   │
   │                                │                             │
   │                                ▼                             │
   │            ┌───── AgentOrchestrator (max 8 turns) ─────┐     │
   │            │                                           │     │
   │            │  Action axis     Provenance axis          │     │
   │            │  ─ ActionExecutor ─ ProvenanceCombiner    │     │
   │            │  ─ ActionAudit    ─ ProvenanceService     │     │
   │            │                                           │     │
   │            │  Constraint axis  (K = 2 retry budget)    │     │
   │            │  ─ ClaimExtractor                         │     │
   │            │  ─ ConstraintChecker                      │     │
   │            │  ─ ViolationLog                           │     │
   │            └───────────────────┬───────────────────────┘     │
   │                                ▼                             │
   │                       Claude tool-calling loop               │
   │                                │                             │
   │                                ▼                             │
   │                    Natural-language response                 │
   └──────────────────────────────────────────────────────────────┘
```

The schema is documented in [`DATABASE_DESIGN.md`](./DATABASE_DESIGN.md);
the migrations under `src/main/resources/db/migration/` are
authoritative.

## Prerequisites

| Requirement | Version |
|---|---|
| Java JDK | 21+ |
| Maven | 3.9+ |
| PostgreSQL | 15+ (running on `localhost:5432`) |
| Python (data generators) | 3.11+ |
| Anthropic API key | https://console.anthropic.com |

## Getting started

```bash
# 1. Clone
git clone https://github.com/Tej-Katika/artha.git
cd artha

# 2. Configure
cp .env.example .env
# Edit .env and set ANTHROPIC_API_KEY

# 3. Apply schema migrations (Flyway is disabled by design;
#    apply each migration manually with psql).
for f in src/main/resources/db/migration/V*.sql; do
  psql -U postgres -d postgres -f "$f"
done

# 4. (Optional) Generate banking synthetic data
python generate_artha_data_v2.py --count 100 --save-map

# 5. (Optional) Hydrate the investments domain
pip install -r data/fetchers/requirements.txt
py -3 data/fetchers/fetch_yahoo.py            # ~5 minutes
mvn spring-boot:run "-Dspring-boot.run.arguments=--load-investments-data"
py -3 data/fetchers/generate_investments.py   # 50 portfolios × 10 archetypes

# 6. Start the service (default port: 8081)
mvn spring-boot:run
```

Verify the service is up:

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP","components":{"db":{"status":"UP",...}}}
```

Send a query to the agent:

```bash
# Banking (default domain)
curl -X POST 'http://localhost:8081/api/agent/chat' \
  -H 'Content-Type: application/json' \
  -d '{"message":"How much did I spend this month?","userId":"<user-uuid>"}'

# Investments
curl -X POST 'http://localhost:8081/api/agent/chat?domain=investments' \
  -H 'Content-Type: application/json' \
  -d '{"message":"Summarize my portfolio.","userId":"<user-uuid>"}'
```

## Configuration

Configuration is via environment variables resolved by Spring's
property placeholders in
[`application.yml`](./src/main/resources/application.yml).

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | _(required)_ | API key for the Claude agent. |
| `ARTHA_EVAL_REFERENCE_DATE` | _(unset)_ | Pin "today" to an ISO date (e.g. `2024-12-31`) so tool query windows are reproducible. Falls back to wall-clock when unset. |
| `ARTHA_ONTOLOGY_TOOLS_ENABLED` | `true` | When `false`, the anomaly, category-insights, financial-health, and subscription tools bypass the enrichment join path and return a degraded response. Used to produce ablation conditions. |

The default model is set in `application.yml`
(`artha.anthropic.model`). The orchestrator falls back to
`claude-haiku-4-5-20251001` when the property is unset; production
configurations typically pin a Sonnet build.

## API reference

The service exposes a REST API on port `8081`.

### Agent

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/agent/chat[?domain=banking\|investments]` | Submit a natural-language query. Body: `{"message": "...", "userId": "<uuid>"}`. Defaults to `banking` if `domain` is omitted. |
| `GET` | `/api/agent/health` | Agent service liveness. |

### Users

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/users` | Create a user. |
| `GET` | `/api/users` | List users. |
| `GET` | `/api/users/{id}` | Get user by ID. |

### Transactions and enrichment (banking)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/transactions` | Insert a transaction. |
| `GET` | `/api/transactions?userId=&from=&to=` | List transactions in a date range. |
| `GET` | `/api/transactions/summary?userId=&from=&to=` | Aggregated spending summary. |
| `POST` | `/api/enrichment/transaction/{id}` | Enrich a single transaction. |
| `POST` | `/api/enrichment/user/{userId}/all?force=true` | (Re-)enrich all transactions for a user. |
| `POST` | `/api/enrichment/user/{userId}/subscriptions?force=true` | Run the subscription detector. |

### Health

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Application health (custom). |
| `GET` | `/actuator/health` | Spring Boot actuator health. |

## Project structure

```
artha/
├── src/main/java/com/artha/
│   ├── core/                             Cross-domain framework
│   │   ├── action/                       Action interface, ActionExecutor, ActionAudit
│   │   ├── provenance/                   ProvenanceValue, Combiner, Service
│   │   ├── constraint/                   Constraint, ClaimExtractor, ConstraintChecker, ViolationLog
│   │   └── agent/                        AgentOrchestrator, ToolRegistry, plugin loader
│   ├── banking/
│   │   ├── ontology/                     9 JPA entities + repositories
│   │   ├── tools/                        21 typed agent tools (read + write)
│   │   ├── actions/                      5 banking Actions (recategorize, create-goal, dismiss-anomaly, update-budget, mark-recurring)
│   │   ├── constraints/                  8 banking Constraints (3 ontology-HARD, 2 numeric, 3 claim-driven SOFT) + RegexClaimExtractor
│   │   ├── provenance/                   Banking-specific provenance resolvers
│   │   └── data/                         Banking enrichment / anomaly / recurring services
│   ├── investments/
│   │   ├── ontology/                     9 JPA entities + repositories
│   │   └── data/                         InvestmentsDataLoader (--load-investments-data)
│   ├── api/                              REST controllers
│   ├── config/                           Spring configuration
│   └── ArthaAgentApplication.java
├── src/main/resources/
│   ├── application.yml                   Configuration
│   ├── db/migration/                     V1 initial schema, V2 action_audit + violation_log, V3 provenance, V4 investments
│   └── investments/tickers.json          50-symbol universe + ^TNX
├── src/test/java/com/artha/              JUnit suite (69 tests; live-LLM ITs gated by ARTHA_LIVE_LLM)
├── data/fetchers/                        Python ETL (yfinance + psycopg2)
│   ├── fetch_yahoo.py                    OHLCV + dividends + risk-free rate
│   ├── generate_investments.py           Synthetic portfolio generator (10 archetypes × 5 users)
│   └── README.md                         Pipeline operating instructions
├── generate_artha_data_v2.py             Banking synthetic data generator
├── ingest_artha_data.py                  Banking ingestion helper
├── pom.xml                               Maven build
├── DATABASE_DESIGN.md                    Schema reference
└── LICENSE                               MIT License
```

## Development

### Build

```bash
mvn clean package
```

The compiled artifact is written to `target/artha-1.0.0-SNAPSHOT.jar`.

### Run from JAR

```bash
java -jar target/artha-1.0.0-SNAPSHOT.jar
```

### Tests

```bash
mvn test
```

The standard suite is 69 tests, hits a live PostgreSQL on
`localhost:5432`, and stays free of API calls. Live-LLM integration
tests under `src/test/java/com/artha/it/` (`*IT` suffix) are skipped
by Surefire's default include patterns; run them explicitly with
`ARTHA_LIVE_LLM=true` set:

```bash
ARTHA_LIVE_LLM=true mvn -Dtest=LlmActionSmokeIT     test
ARTHA_LIVE_LLM=true mvn -Dtest=LlmConstraintSmokeIT test
```

### Code style

The project uses standard Spring Boot conventions: constructor
injection via Lombok `@RequiredArgsConstructor`, `@Component`/
`@Service` stereotypes, Spring Data JPA repositories, and
`@Transactional` boundaries on write paths. Lazy-loaded associations
in constraints and tools are wrapped in `@Transactional(readOnly =
true)` to keep Hibernate sessions open outside the open-session-in-
view scope.

### Database migrations

Flyway is disabled (`spring.flyway.enabled: false`); migrations under
`src/main/resources/db/migration/` are applied manually with `psql
-f`. The current versions are V1 (initial schema), V2 (action_audit
+ violation_log), V3 (provenance on enrichments), V4 (investments
ontology).

## License

Released under the MIT License — see [`LICENSE`](./LICENSE) for the
full text.
