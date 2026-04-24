# Artha

**A domain ontology-driven agentic framework for LLM-based personal
finance reasoning.**

Artha enriches raw banking transactions into typed ontology objects
(merchant profiles, spending categories, recurring bills, anomaly
flags, budgets, and goals) before any LLM reasoning, and exposes
those objects to a Claude agent through 15 typed tools.

> The original package name was "FinWise"; the project has been
> rebranded to "Artha". During the migration, Java packages may
> appear in either `com.finwise.*` or `com.artha.*` form depending
> on the branch.

## Tech stack

- **Backend**: Java 21, Spring Boot 3.2.x, Spring Data JPA
- **Database**: PostgreSQL 15
- **LLM**: Anthropic Claude API (agent: `claude-sonnet-4-6`)
- **Data generator**: Python 3.11+

## Quick start

### Prerequisites

- Java 21+ (OpenJDK / Oracle)
- Maven 3.9+
- PostgreSQL 15+
- An Anthropic API key (https://console.anthropic.com)

### Install and configure

```bash
cp .env.example .env
# Edit .env and set ANTHROPIC_API_KEY
```

Create the database (defaults to `postgres` on `localhost:5432`):

```sql
CREATE DATABASE postgres;
-- or use any existing DB; see application.yml
```

### Generate synthetic data (optional)

```bash
python generate_finwise_data_v2.py --count 100 --save-map
```

This produces 100 synthetic user profiles with realistic
transaction histories across 10 behavioral archetypes and writes
the user-UUID-to-archetype map to `finwise_users_v2.json`.

### Run the service

```bash
mvn spring-boot:run
```

The service comes up on `http://localhost:8081` (see
`application.yml`). Health check:

```bash
curl http://localhost:8081/actuator/health
```

### Talk to the agent

```bash
curl -X POST http://localhost:8081/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"How much did I spend this month?","userId":"<user-uuid>"}'
```

## Architecture

```
Raw transactions  ─────▶  Ontology enrichment
(bank / generator)          ─ rule-based classification
                            ─ upstream-metadata fallback
                            ─ statistical anomaly (z > 2.5)
                            ─ recurring-bill detector
                                     │
                                     ▼
                      Typed ontology (PostgreSQL)
                      9 object types, 7 primary relationships
                                     │
                                     ▼
                      15 typed agent tools
                                     │
                                     ▼
                      Claude Sonnet agent
                      (bounded tool-call loop)
                                     │
                                     ▼
                      Natural-language response
```

See [DATABASE_DESIGN.md](./DATABASE_DESIGN.md) for the ontology
schema.

## Layout

```
src/main/java/com/finwise/
    agent/
        core/                 Orchestrator, feature flags,
                              tool interfaces
        ontology/             Enrichment service,
                              anomaly detector,
                              subscription detector
        tools/                15 typed tools (Get*Tool.java)
        api/                  Spring REST controllers
        domain/               JPA entities + repositories
src/main/resources/
    application.yml           Configuration
    db/migration/             Flyway migrations
generate_finwise_data*.py     Synthetic data generator
ingest_finwise_data.py        Data ingestion helper
docker-compose.yml            Local dev dependencies
```

## Configuration (env vars)

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | (required) | Agent LLM calls |
| `ARTHA_EVAL_REFERENCE_DATE` | (unset) | ISO date to pin "today" for reproducible benchmarks; unset in prod |
| `ARTHA_ONTOLOGY_TOOLS_ENABLED` | `true` | When `false`, bypasses ontology paths in anomaly/category/health/subscription tools |

## Research materials

Paper, evaluation harness, scoring rubric, full ablation results,
and reproduction scripts are maintained **separately** and are not
included in this repository. Contact the author for access to the
research artifacts.

## License

TBD — add a `LICENSE` file before wider distribution.

## Contact

Tejas Katika — tejashwar1029@gmail.com
Department of Computer Science, University of North Texas
