# Artha

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)
[![Anthropic](https://img.shields.io/badge/LLM-Claude%20Sonnet%204.6-purple.svg)](https://www.anthropic.com/claude)

A domain-ontology-driven agentic framework for LLM-based personal finance
reasoning. Artha enriches raw bank transactions into typed ontology
objects (merchant profiles, spending categories, recurring bills, anomaly
flags, budgets, and goals) at ingestion, and exposes them to a Claude
agent through a typed tool layer.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Project Structure](#project-structure)
- [Development](#development)
- [License](#license)

## Features

- **Typed ontology layer** — 9 financial object types and 7 primary
  relationships modeled in PostgreSQL via JPA entities and join tables.
- **Multi-source enrichment** — rule-based merchant classification with
  upstream-metadata fallback, statistical anomaly detection (z-score),
  and recurring-bill detection.
- **Typed agent tools** — 15 tools surface ontology objects to a
  Claude Sonnet 4.6 agent through a bounded tool-calling loop.
- **Reproducible inference** — feature flag and reference-date overrides
  for benchmark and ablation runs.
- **Open synthetic data** — Python generator produces 10-archetype
  user profiles calibrated against publicly documented spending
  distributions.

## Architecture

```
   Raw transactions
   (bank API / generator)
            │
            ▼
   Enrichment Engine            ┌─────────────────────────────────┐
   ─ rule-based classification  │  9 ontology types               │
   ─ metadata fallback          │  ─ Transaction                  │
   ─ statistical anomaly        │  ─ TransactionEnrichment        │
   ─ recurring-bill detection   │  ─ MerchantProfile / Type       │
            │                   │  ─ SpendingCategory             │
            ▼                   │  ─ ClassificationRule           │
   PostgreSQL ontology  ────────┤  ─ RecurringBill                │
            │                   │  ─ Budget                       │
            ▼                   │  ─ FinancialGoal                │
   15 typed agent tools         └─────────────────────────────────┘
            │
            ▼
   Claude Sonnet 4.6 agent
   (max 8 tool iterations)
            │
            ▼
   Natural-language response
```

See [`DATABASE_DESIGN.md`](./DATABASE_DESIGN.md) for the full schema.

## Prerequisites

| Requirement | Version |
|---|---|
| Java JDK | 21+ |
| Maven | 3.9+ |
| PostgreSQL | 15+ |
| Python (data generator only) | 3.11+ |
| Anthropic API key | https://console.anthropic.com |

## Getting Started

```bash
# 1. Clone
git clone https://github.com/Tej-Katika/artha.git
cd artha

# 2. Configure
cp .env.example .env
# Edit .env and set ANTHROPIC_API_KEY

# 3. Initialize the database (defaults to `postgres` on localhost:5432)
psql -U postgres -d postgres -f src/main/resources/db/migration/V1__initial_schema.sql

# 4. (Optional) Generate synthetic data
python generate_artha_data_v2.py --count 100 --save-map

# 5. Start the service
mvn spring-boot:run
```

Verify the service is up:

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP","components":{"db":{"status":"UP",...}}}
```

Send a query to the agent:

```bash
curl -X POST http://localhost:8081/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"How much did I spend this month?","userId":"<user-uuid>"}'
```

## Configuration

All configuration is via environment variables (resolved by Spring's
property placeholders in [`application.yml`](./src/main/resources/application.yml)):

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | _(required)_ | API key for the Claude agent |
| `ARTHA_EVAL_REFERENCE_DATE` | _(unset)_ | Pin "today" to an ISO date (e.g. `2024-12-31`) so tool query windows are reproducible. Falls back to wall-clock when unset. |
| `ARTHA_ONTOLOGY_TOOLS_ENABLED` | `true` | When `false`, the anomaly, category-insights, financial-health, and subscription tools bypass the ontology join path and return degraded responses. |

Connection settings (datasource URL, credentials, server port) are
declared in [`application.yml`](./src/main/resources/application.yml).
Override via Spring Boot's standard configuration mechanisms (env
vars, profiles, command-line args).

## API Reference

The service exposes a REST API on port `8081` (configurable).

### Agent

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/agent/chat` | Submit a natural-language query. Body: `{"message": "...", "userId": "<uuid>"}` |
| `GET` | `/api/agent/health` | Agent service liveness |

### Users

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/users` | Create a user. Body: `{"email": "...", "fullName": "..."}` |
| `GET` | `/api/users` | List users |
| `GET` | `/api/users/{id}` | Get user by ID |

### Transactions

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/transactions` | Insert a transaction |
| `GET` | `/api/transactions?userId=&from=&to=` | List transactions in a date range |
| `GET` | `/api/transactions/summary?userId=&from=&to=` | Aggregated spending summary |

### Enrichment

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/enrichment/transaction/{id}` | Enrich a single transaction |
| `GET` | `/api/enrichment/transaction/{id}` | Read enrichment for a transaction |
| `POST` | `/api/enrichment/user/{userId}/all?force=true` | (Re-)enrich all transactions for a user |
| `POST` | `/api/enrichment/user/{userId}/subscriptions?force=true` | Run the subscription detector |

### Health

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Application health (custom) |
| `GET` | `/actuator/health` | Spring Boot actuator health |

## Project Structure

```
artha/
├── src/main/java/com/artha/agent/
│   ├── api/              REST controllers
│   ├── core/              Agent orchestrator, feature flags, tool interfaces
│   ├── domain/            JPA entities and repositories
│   ├── enrichment/        Goal-impact and similar enrichment services
│   ├── ontology/          Enrichment service, anomaly detector, subscription detector
│   ├── tools/             15 typed Get*Tool implementations
│   └── ArthaAgentApplication.java
├── src/main/resources/
│   ├── application.yml    Configuration
│   ├── db/migration/      Flyway-style schema migrations
│   └── prompts/           System prompts for the agent
├── generate_artha_data*.py    Synthetic data generators
├── ingest_artha_data.py       Data ingestion helper
├── docker-compose.yml         Local dev dependencies
├── pom.xml                    Maven build
├── DATABASE_DESIGN.md         Ontology schema reference
├── SCORING_RUBRIC.md          LLM-as-judge evaluation rubric
└── LICENSE                    MIT License
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

### Code style

The project uses standard Spring Boot conventions: constructor
injection via Lombok `@RequiredArgsConstructor`, `@Component`/`@Service`
stereotypes, Spring Data JPA repositories, and `@Transactional`
boundaries on write paths.

### Database migrations

Schema migrations live in `src/main/resources/db/migration/`. Apply
manually with `psql -f` or wire up Flyway in your runtime profile.

## License

Released under the MIT License — see [`LICENSE`](./LICENSE) for the
full text.
