-- One row per uploaded file. Tracks the processing status of each document.
CREATE TABLE documents (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(50)  NOT NULL DEFAULT 'default',   -- who owns the document (tenant isolation)
    file_name       VARCHAR(255) NOT NULL,
    title           VARCHAR(255) NOT NULL,
    version         VARCHAR(30)  NOT NULL DEFAULT '1.0',       -- policy version, e.g. "2025-01"
    content_type    VARCHAR(100) NOT NULL,                     -- application/pdf or DOCX type
    file_size       BIGINT       NOT NULL,                     -- bytes
    file_hash       CHAR(64)     NOT NULL,                     -- SHA-256 of the file, used to detect duplicates
    storage_path    VARCHAR(500) NOT NULL,                     -- where the original file is saved on disk
    status          VARCHAR(20)  NOT NULL,                     -- see CHECK below
    status_message  VARCHAR(1000),                             -- error text when status = FAILED
    page_count      INTEGER,
    chunk_count     INTEGER,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_documents_status
        CHECK (status IN ('QUEUED', 'PARSING', 'EMBEDDING', 'COMPLETED', 'FAILED')),

    -- Same file uploaded twice by the same tenant is rejected (idempotent ingestion)
    CONSTRAINT uq_documents_tenant_hash UNIQUE (tenant_id, file_hash)
);

CREATE INDEX idx_documents_status ON documents (status);
