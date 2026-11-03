-- One row per question: how many tokens it used, what it probably cost, how long it took.
--
-- Kept in its own table on purpose (not in chat_messages): conversations are deleted after the retention
-- period, but the cost history of a tenant must stay. The table contains NO text, NO user id and nothing
-- else that is personal: only numbers and the tenant.
CREATE TABLE usage_records (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    outcome           VARCHAR(20)    NOT NULL,       -- answered, degraded, not_found
    prompt_tokens     INTEGER        NOT NULL DEFAULT 0,   -- tokens sent to the language model
    completion_tokens INTEGER        NOT NULL DEFAULT 0,   -- tokens written by the language model
    embedding_tokens  INTEGER        NOT NULL DEFAULT 0,   -- tokens sent to the embedding model (0 when the vector came from the cache)
    cost_usd          NUMERIC(12, 6) NOT NULL DEFAULT 0,   -- estimate, see app.cost in application.yml
    duration_ms       INTEGER        NOT NULL DEFAULT 0
);

CREATE INDEX idx_usage_records_tenant_time ON usage_records (tenant_id, created_at);
