package com.example.ragapp.evaluation;

import java.util.ArrayList;
import java.util.List;

import com.example.ragapp.retrieval.RetrievalService;
import com.example.ragapp.retrieval.RetrievedChunk;

/**
 * Measures the SEARCH alone (no language model): does the page that contains the answer appear among the
 * chunks the search returns, and how high?
 *
 *   hit@1  how often the right page is the very first result
 *   hit@5  how often the right page is somewhere in the 5 results given to the model
 *   MRR    "mean reciprocal rank": 1 for first place, 1/2 for second, 1/3 for third ... 0 when missing.
 *          One number that rewards finding the right page EARLY.
 *
 * If the right page is not in the results, the model cannot answer correctly, however good it is.
 * That is why retrieval is measured separately: it shows whether a wrong answer was a search problem.
 */
public final class RetrievalEvaluator {

    private RetrievalEvaluator() {
    }

    /** @param misses the questions where the right page was not first, with the rank found (0 = not found) */
    public record Result(int questions, double hitAt1, double hitAt5, double mrr, List<String> misses) {
        @Override
        public String toString() {
            return String.format("hit@1=%.2f hit@5=%.2f MRR=%.3f (%d questions)", hitAt1, hitAt5, mrr, questions);
        }
    }

    /** Runs the questions that have an expected page, one by one, as a first question of a conversation. */
    public static Result evaluate(RetrievalService retrieval, String tenantId, List<EvalCase> cases) {
        int total = 0;
        int first = 0;
        int inFive = 0;
        double reciprocalRankSum = 0;
        List<String> misses = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            if (evalCase.expectedPage() == null) {
                continue;
            }
            total++;
            int rank = rankOf(retrieval.search(tenantId, evalCase.question()).chunks(), evalCase.expectedPage());
            if (rank == 1) {
                first++;
            } else {
                misses.add(evalCase.id() + " (rank " + rank + ")");
            }
            if (rank >= 1 && rank <= 5) {
                inFive++;
            }
            if (rank >= 1) {
                reciprocalRankSum += 1.0 / rank;
            }
        }
        return new Result(total, ratio(first, total), ratio(inFive, total), ratio(reciprocalRankSum, total), misses);
    }

    /** Position (1 = first) of the first chunk from the expected page; 0 when there is none. */
    static int rankOf(List<RetrievedChunk> chunks, int expectedPage) {
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).pageNumber() != null && chunks.get(i).pageNumber() == expectedPage) {
                return i + 1;
            }
        }
        return 0;
    }

    private static double ratio(double part, int total) {
        return total == 0 ? 0 : part / total;
    }
}
