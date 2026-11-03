package com.example.ragapp.common;

import java.util.UUID;

/**
 * A citation: where a part of the answer came from, so the user can verify it.
 *
 * @param number  the [n] marker used in the answer text
 * @param snippet short preview of the source text
 */
public record SourceReference(
        int number,
        UUID documentId,
        String fileName,
        String title,
        String version,
        Integer pageNumber,
        String section,
        String snippet,
        double score) {
}
