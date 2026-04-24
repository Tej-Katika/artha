-- Artha AI Agent — Database Schema
-- Migration V1: Initial schema

-- ── Extensions ────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;  -- pgvector for memory embeddings

-- ── Users ─────────────────────────────────────────────────────
CREATE TABLE users (
    user_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255),
    risk_profile    VARCHAR(20) DEFAULT 'MODERATE'
                    CHECK (risk_profile IN ('CONSERVATIVE', 'MODERATE', 'AGGRESSIVE')),
    income_bracket  VARCHAR(20) DEFAULT 'UNKNOWN'
                    CHECK (income_bracket IN ('UNDER_50K', '50K_100K', '100K_200K', 'OVER_200K', 'UNKNOWN')),
    plaid_access_token  BYTEA,         -- AES-256 encrypted
    plaid_item_id       VARCHAR(255),
    plaid_connected     BOOLEAN DEFAULT FALSE,
    notification_email  VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Conversations ─────────────────────────────────────────────
CREATE TABLE conversations (
    conversation_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    title           VARCHAR(255),       -- Auto-generated from first message
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversations_user_id ON conversations(user_id);
CREATE INDEX idx_conversations_updated ON conversations(last_updated_at DESC);

-- ── Messages ──────────────────────────────────────────────────
CREATE TABLE messages (
    message_id      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
    content         JSONB NOT NULL,     -- Stores full message content including tool_use/tool_result blocks
    token_count     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX idx_messages_created_at ON messages(conversation_id, created_at ASC);

-- ── Tool Calls (audit log) ────────────────────────────────────
CREATE TABLE tool_calls (
    tool_call_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id      UUID REFERENCES messages(message_id) ON DELETE SET NULL,
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    tool_name       VARCHAR(100) NOT NULL,
    input_params    JSONB,
    output          JSONB,
    latency_ms      INTEGER,
    success         BOOLEAN NOT NULL DEFAULT TRUE,
    error_message   TEXT,
    called_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tool_calls_user_id ON tool_calls(user_id);
CREATE INDEX idx_tool_calls_tool_name ON tool_calls(tool_name);
CREATE INDEX idx_tool_calls_called_at ON tool_calls(called_at DESC);

-- ── Financial Goals ───────────────────────────────────────────
CREATE TABLE financial_goals (
    goal_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    goal_type       VARCHAR(50) NOT NULL
                    CHECK (goal_type IN ('HOME_DOWN_PAYMENT', 'RETIREMENT', 'EMERGENCY_FUND',
                                         'EDUCATION', 'VACATION', 'DEBT_PAYOFF', 'INVESTMENT', 'OTHER')),
    target_amount   DECIMAL(15,2) NOT NULL,
    current_amount  DECIMAL(15,2) NOT NULL DEFAULT 0,
    monthly_contribution DECIMAL(15,2),
    target_date     DATE,
    status          VARCHAR(20) DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'ON_TRACK', 'BEHIND', 'ACHIEVED', 'PAUSED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_goals_user_id ON financial_goals(user_id);

-- ── Budget Alerts ─────────────────────────────────────────────
CREATE TABLE budget_alerts (
    alert_id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    category        VARCHAR(100) NOT NULL,
    threshold_amount DECIMAL(15,2) NOT NULL,
    threshold_pct   INTEGER,           -- Alternative: alert at X% of budget
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    last_triggered_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alerts_user_id ON budget_alerts(user_id);

-- ── Transactions (cached from Plaid) ──────────────────────────
CREATE TABLE transactions (
    transaction_id  VARCHAR(255) PRIMARY KEY,   -- Plaid transaction_id
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    account_id      VARCHAR(255) NOT NULL,
    amount          DECIMAL(15,2) NOT NULL,
    currency        VARCHAR(3) DEFAULT 'USD',
    date            DATE NOT NULL,
    name            VARCHAR(500),               -- Merchant name
    merchant_name   VARCHAR(255),
    category        VARCHAR(255),               -- Plaid primary category
    category_detail VARCHAR(255),               -- Plaid detailed category
    pending         BOOLEAN DEFAULT FALSE,
    location_city   VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_user_id ON transactions(user_id);
CREATE INDEX idx_transactions_date ON transactions(user_id, date DESC);
CREATE INDEX idx_transactions_category ON transactions(user_id, category);

-- ── Financial Memory (pgvector) ───────────────────────────────
CREATE TABLE memory_chunks (
    chunk_id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    content         TEXT NOT NULL,              -- The actual memory text
    embedding       vector(1536),               -- OpenAI text-embedding-3-small dimensions
    source          VARCHAR(50) DEFAULT 'CONVERSATION'
                    CHECK (source IN ('CONVERSATION', 'GOAL_UPDATE', 'PREFERENCE', 'SYSTEM')),
    source_conversation_id UUID REFERENCES conversations(conversation_id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_memory_user_id ON memory_chunks(user_id);
-- Vector similarity search index (IVFFlat for approximate nearest neighbor)
CREATE INDEX idx_memory_embedding ON memory_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ── Pending Confirmations (for WRITE tool actions) ────────────
CREATE TABLE pending_confirmations (
    confirmation_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    tool_name       VARCHAR(100) NOT NULL,
    tool_input      JSONB NOT NULL,
    description     TEXT NOT NULL,              -- Human-readable action description
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '10 minutes'),
    confirmed       BOOLEAN,
    responded_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_confirmations_user_id ON pending_confirmations(user_id, expires_at);

-- ── Trigger: auto-update updated_at ───────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_goals_updated_at BEFORE UPDATE ON financial_goals
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
