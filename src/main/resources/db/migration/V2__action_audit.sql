-- Artha v2 — ActionAudit append-only log.
--
-- Run manually before activating the Action axis (Week 3+).
-- ddl-auto=none, so this is the source of truth for schema.
--
-- Notes:
--   * input_json / output_json are TEXT, not JSONB, to keep ingest
--     fast even when the agent passes large payloads. Indexed
--     queries on these go through application-side projections
--     instead of GIN indices.
--   * No FK to users(id) — audit must survive user deletion.

CREATE TABLE IF NOT EXISTS action_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_name     VARCHAR(80)  NOT NULL,
    domain          VARCHAR(20)  NOT NULL,
    actor           VARCHAR(20)  NOT NULL,
    user_id         UUID,
    session_id      VARCHAR(80),
    input_json      TEXT,
    output_json     TEXT,
    outcome         VARCHAR(30)  NOT NULL,
    error_message   TEXT,
    started_at      TIMESTAMPTZ  NOT NULL,
    ended_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_outcome CHECK (outcome IN (
        'SUCCESS',
        'FAILURE_PRECONDITION',
        'FAILURE_EXECUTION',
        'FAILURE_POSTCONDITION',
        'ROLLED_BACK'
    )),
    CONSTRAINT chk_actor CHECK (actor IN ('AGENT', 'USER', 'SYSTEM')),
    CONSTRAINT chk_domain CHECK (domain IN ('banking', 'investments'))
);

CREATE INDEX IF NOT EXISTS idx_action_audit_user_started
    ON action_audit (user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_action_audit_session
    ON action_audit (session_id, started_at);

CREATE INDEX IF NOT EXISTS idx_action_audit_action_outcome
    ON action_audit (action_name, outcome);
