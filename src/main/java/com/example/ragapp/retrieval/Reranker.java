package com.example.ragapp.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Second look at the best candidates, to put the most relevant first.
 *
 * Stage 1 (hybrid search) is built to FIND everything that might be relevant, quickly.
 * Stage 2 (this class) looks closer at that short list. Each candidate gets a score from two opinions:
 *
 *   - meaning:  how similar the chunk is to the question (from the vector search)
 *   - coverage: how many of the question's words appear in the chunk. Words that appear in
 *               every candidate count less, because they cannot tell the candidates apart.
 *
 *     score = 0.5 * meaning + 0.5 * coverage
 *
 * A large "cross-encoder" model would be more accurate, but it needs an extra model and is slow
 * on a CPU. This simple version has no extra cost and is easy to understand.
 */
@Component
public class Reranker {

    private static final double MEANING_WEIGHT = 0.5;
    private static final double COVERAGE_WEIGHT = 0.5;

    /** A candidate with its new score (0 to 1). */
    public record RankedHit(SearchHit hit, double score) {
    }

    public List<RankedHit> rerank(String question, List<RankFusion.FusedHit> candidates) {
        Set<String> questionWords = TextWords.words(question);
        List<Set<String>> chunkWords = candidates.stream().map(c -> TextWords.words(c.hit().text())).toList();

        // weight of a question word = 1 / number of candidates that contain it
        Map<String, Double> weights = new HashMap<>();
        double totalWeight = 0;
        for (String word : questionWords) {
            long containing = chunkWords.stream().filter(words -> words.contains(word)).count();
            double weight = 1.0 / Math.max(1, containing);
            weights.put(word, weight);
            totalWeight += weight;
        }

        List<RankedHit> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double covered = 0;
            for (String word : questionWords) {
                if (chunkWords.get(i).contains(word)) {
                    covered += weights.get(word);
                }
            }
            double coverage = totalWeight == 0 ? 0 : covered / totalWeight;
            double meaning = Math.max(0, Math.min(1, candidates.get(i).hit().similarity()));
            ranked.add(new RankedHit(candidates.get(i).hit(), MEANING_WEIGHT * meaning + COVERAGE_WEIGHT * coverage));
        }
        ranked.sort(Comparator.comparingDouble(RankedHit::score).reversed());
        return ranked;
    }
}
