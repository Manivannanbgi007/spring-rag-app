-- Fix for V5: PostgreSQL reads "AB-1007" as the two words "ab" and "-1007" (a negative number),
-- so a search for "1007" never found it. The same happens with "HR-204" and with dates.
--
-- The fix: before the text is indexed, a hyphen between two letters or digits becomes a space.
-- "AB-1007" -> "AB 1007" -> words "ab" and "1007".  "full-time" -> "full" and "time".
-- The search query (TextQueryBuilder) already splits words at every symbol, so both sides now agree.
DROP INDEX idx_vector_store_content_tsv;
ALTER TABLE vector_store DROP COLUMN content_tsv;

ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english',
                    regexp_replace(coalesce(content, ''), '(?<=[[:alnum:]])-(?=[[:alnum:]])', ' ', 'g'))
    ) STORED;

CREATE INDEX idx_vector_store_content_tsv ON vector_store USING gin (content_tsv);
