-- A conversation = one chat session of one user. Messages and feedback belong to it.
-- ON DELETE CASCADE: deleting a conversation also deletes its messages and their feedback.
CREATE TABLE conversations (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(50)  NOT NULL,
    owner_id         VARCHAR(100) NOT NULL,       -- who started it; only this user can see it
    title            VARCHAR(200) NOT NULL,       -- first question, shortened
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversations_owner    ON conversations (tenant_id, owner_id, last_activity_at DESC);
CREATE INDEX idx_conversations_activity ON conversations (last_activity_at);   -- used by the retention clean-up

CREATE TABLE chat_messages (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id      UUID         NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role                 VARCHAR(10)  NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content              TEXT         NOT NULL,
    found                BOOLEAN,                  -- assistant messages: was the answer found in the documents?
    standalone_question  VARCHAR(1000),            -- user messages: the question after follow-up rewriting
    sources_json         TEXT,                     -- assistant messages: the citations, as JSON
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_messages_conversation ON chat_messages (conversation_id, id);

-- At most one feedback per answer. Sending feedback again replaces it.
CREATE TABLE feedback (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id         BIGINT       NOT NULL UNIQUE REFERENCES chat_messages (id) ON DELETE CASCADE,
    rating             VARCHAR(10)  NOT NULL CHECK (rating IN ('UP', 'DOWN')),
    comment            VARCHAR(1000),
    reported_incorrect BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
