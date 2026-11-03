package com.example.ragapp.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Combines the two result lists into one, using "Reciprocal Rank Fusion" (RRF).
 *
 * The two searches use different scores (a similarity between 0 and 1, and a keyword rank
 * with no fixed range), so their scores cannot be added. Their POSITIONS can:
 *
 *     score of a chunk = sum over the lists of  1 / (k + position in that list)
 *
 * A chunk near the top of both lists gets the highest score. A chunk found by only one search
 * is still kept, which is the point of hybrid search: a policy code like "HR-204" is found by
 * the keyword search even when the meaning search does not rank it.
 * "k" (usually 60) softens the difference between the first and the second place.
 */
public final class RankFusion {

    private RankFusion() {
    }

    /** A chunk with its combined score and which searches found it. */
    public record FusedHit(SearchHit hit, double rrfScore, boolean foundByVector, boolean foundByText) {
    }

    public static List<FusedHit> fuse(List<SearchHit> vectorHits, List<SearchHit> textHits, int k) {
        Map<UUID, Accumulator> byId = new LinkedHashMap<>();
        add(byId, vectorHits, k, true);
        add(byId, textHits, k, false);

        return byId.values().stream()
                .map(a -> new FusedHit(a.hit, a.score, a.byVector, a.byText))
                .sorted(Comparator.comparingDouble(FusedHit::rrfScore).reversed())
                .toList();
    }

    private static void add(Map<UUID, Accumulator> byId, List<SearchHit> hits, int k, boolean vectorList) {
        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            Accumulator acc = byId.computeIfAbsent(hit.id(), id -> new Accumulator(hit));
            acc.score += 1.0 / (k + index + 1);   // positions start at 1
            if (vectorList) {
                acc.byVector = true;
            } else {
                acc.byText = true;
            }
        }
    }

    private static final class Accumulator {
        private final SearchHit hit;
        private double score;
        private boolean byVector;
        private boolean byText;

        private Accumulator(SearchHit hit) {
            this.hit = hit;
        }
    }
}
