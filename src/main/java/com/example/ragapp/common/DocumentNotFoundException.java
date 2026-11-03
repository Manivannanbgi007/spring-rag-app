package com.example.ragapp.common;

import java.util.UUID;

/** No document with this id exists for the tenant. Returned as HTTP 404. */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id);
    }
}
