-- ═══════════════════════════════════════════════════════════════
--  FinWise PostgreSQL Initialization Script
--  Runs once when the Docker container is first created.
--  Flyway migrations (V1, V2...) run after this on app startup.
-- ═══════════════════════════════════════════════════════════════

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";        -- UUID generation
CREATE EXTENSION IF NOT EXISTS vector;              -- pgvector for semantic memory
CREATE EXTENSION IF NOT EXISTS pg_stat_statements; -- Query performance monitoring

-- Read-only role for analytics (optional, good practice)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finwise_readonly') THEN
        CREATE ROLE finwise_readonly;
    END IF;
END
$$;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO finwise_readonly;
