package com.example.ragapp.retrieval;

import java.util.List;

/**
 * What the search found.
 *
 * @param degraded        true when the search could not use all its parts (the embedding model was
 *                        unavailable, so only the keyword search ran). The result is still useful,
 *                        but it may be less complete, and the user should be told.
 * @param embeddingTokens tokens the embedding model used for this search (0 if the vector came from the cache)
 */
public record RetrievalResult(List<RetrievedChunk> chunks, boolean degraded, int embeddingTokens) {
}
