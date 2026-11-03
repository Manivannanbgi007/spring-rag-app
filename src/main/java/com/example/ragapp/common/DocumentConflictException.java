package com.example.ragapp.common;

import java.util.UUID;

/**
 * The request clashes with the current state: the same file was already uploaded,
 * or the document is still being processed. Returned as HTTP 409.
 */
public class DocumentConflictException extends RuntimeException {

    private final UUID existingDocumentId;

    public DocumentConflictException(String message, UUID existingDocumentId) {
        super(message);
        this.existingDocumentId = existingDocumentId;
    }

    public UUID getExistingDocumentId() {
        return existingDocumentId;
    }
}
