package com.example.ragapp.retrieval;

import java.util.UUID;

/**
 * One piece of document text found by the search, with where it came from.
 *
 * @param pageNumber PDF page, or null (DOCX)
 * @param section    DOCX heading path, or null (PDF)
 * @param score      similarity, 0 to 1 (higher = closer to the question)
 */
public record RetrievedChunk(
        UUID documentId,
        String fileName,
        String title,
        String version,
        Integer pageNumber,
        String section,
        String text,
        double score) {
}
