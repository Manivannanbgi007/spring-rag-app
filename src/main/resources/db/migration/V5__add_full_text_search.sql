-- Keyword search for hybrid search.
-- The database keeps a "tsvector" (a list of the words of each chunk, reduced to their stems, for
-- example "days" -> "day") up to date by itself. The GIN index makes word lookups fast.
-- It uses the "english" text configuration, so the query side (ChunkSearchRepository) must use it too.
ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;

CREATE INDEX idx_vector_store_content_tsv ON vector_store USING gin (content_tsv);
