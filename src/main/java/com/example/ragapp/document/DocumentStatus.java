package com.example.ragapp.document;

/** Where a document is in its processing life. Stored in the documents.status column. */
public enum DocumentStatus {
    QUEUED,      // saved, waiting for a background worker
    PARSING,     // reading text from the file and splitting it into chunks
    EMBEDDING,   // converting chunks to vectors and saving them
    COMPLETED,   // ready for questions
    FAILED;      // something went wrong, see statusMessage

    public boolean isInProgress() {
        return this == QUEUED || this == PARSING || this == EMBEDDING;
    }
}
