package com.example.ragapp.document;

import java.time.Instant;
import java.util.UUID;

/** What the API returns for a document. We never expose the entity directly. */
public record DocumentResponse(
        UUID id,
        String fileName,
        String title,
        String version,
        DocumentStatus status,
        String statusMessage,
        Integer pageCount,
        Integer chunkCount,
        long fileSize,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentResponse from(PolicyDocument d) {
        return new DocumentResponse(d.getId(), d.getFileName(), d.getTitle(), d.getVersion(),
                d.getStatus(), d.getStatusMessage(), d.getPageCount(), d.getChunkCount(),
                d.getFileSize(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
