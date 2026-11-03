package com.example.ragapp.ingestion;

/**
 * A document cannot be processed for a reason the user can understand
 * (for example: too many pages, no readable text). The message is shown to the user
 * as the document's statusMessage.
 */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }
}
