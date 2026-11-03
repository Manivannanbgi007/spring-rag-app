-- One row per chunk of text. Spring AI's PgVectorStore reads and writes this table.
-- Column names are fixed by Spring AI: id, content, metadata, embedding.
CREATE TABLE vector_store (
    id        UUID PRIMARY KEY,
    content   TEXT,                 -- the chunk text
    metadata  JSONB,                -- document_id, file_name, page_number, tenant_id, ...
    embedding VECTOR(768)           -- 768 = size of nomic-embed-text vectors
);

-- Fast "find the most similar chunks" search (HNSW index, cosine distance)
CREATE INDEX idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- Fast filtering by document and tenant (used for delete and tenant isolation)
CREATE INDEX idx_vector_store_document_id ON vector_store ((metadata ->> 'document_id'));
CREATE INDEX idx_vector_store_tenant_id   ON vector_store ((metadata ->> 'tenant_id'));
