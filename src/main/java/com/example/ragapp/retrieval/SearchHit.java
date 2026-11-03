package com.example.ragapp.retrieval;

import java.util.Map;
import java.util.UUID;

/**
 * One raw search result: a chunk found by the vector search or by the keyword search.
 *
 * @param similarity how close the chunk is in meaning to the question, 0 to 1
 * @param textRank   how well the chunk matches the question's keywords (0 when found by vector search only)
 */
public record SearchHit(UUID id, String text, Map<String, Object> metadata, double similarity, double textRank) {
}
