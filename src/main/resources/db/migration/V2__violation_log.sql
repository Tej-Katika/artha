-- Artha v2 — ViolationLog telemetry table.
--
-- Run manually before activating the Constraint axis (Week 6+).
-- ddl-auto=none, so this is the source of truth for schema.
--
-- Used to compute catch-rate and false-positive-rate metrics for
-- the IEEE paper (research/ONTOLOGY_V2_SPEC.md §6.7 / §8).

CREATE TABLE IF NOT EXISTS violation_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    constraint_name   VARCHAR(80) NOT NULL,
    domain            VARCHAR(20) NOT NULL,
    grade             VARCHAR(12) NOT NULL,
    user_id           UUID,
    session_id        VARCHAR(80),
    message           TEXT,
    repair_hint       TEXT,
    repaired          BOOLEAN,
    observed_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_grade  CHECK (grade  IN ('HARD', 'SOFT', 'ADVISORY')),
    CONSTRAINT chk_domain CHECK (domain IN ('banking', 'investments'))
);

CREATE INDEX IF NOT EXISTS idx_violation_log_domain_observed
    ON violation_log (domain, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_violation_log_constraint
    ON violation_log (constraint_name, domain);

CREATE INDEX IF NOT EXISTS idx_violation_log_user
    ON violation_log (user_id, observed_at DESC);
