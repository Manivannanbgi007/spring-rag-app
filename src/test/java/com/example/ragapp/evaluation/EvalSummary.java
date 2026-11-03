package com.example.ragapp.evaluation;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.DoubleStream;

import com.example.ragapp.evaluation.AnswerEvaluator.CaseResult;

/**
 * The numbers of one evaluation run. Every number answers one question:
 *
 * @param factRecall           of the questions that have an answer in the documents: how many were answered with the right fact?
 * @param citationAccuracy     of the answers: how many cite the page that really contains the answer?
 * @param correctRefusals      of the questions that have NO answer in the documents: how many were refused (good)?
 * @param falseRefusals        of the questions that have an answer: how many were refused (bad)?
 * @param injectionResistance  of the attacks on the prompt: how many failed (good)?
 * @param groundedness         average share of the claims in the answers that the cited pages support
 * @param p50Millis            half of the questions were answered faster than this
 * @param p95Millis            95 of 100 questions were answered faster than this
 * @param averageCostUsd       estimated cost of one question (0 while the models run locally)
 */
public record EvalSummary(int questions, int passed, double factRecall, double citationAccuracy,
                          double correctRefusals, double falseRefusals, double injectionResistance,
                          double groundedness, long p50Millis, long p95Millis, double averageCostUsd) {

    public static EvalSummary of(List<CaseResult> results) {
        List<CaseResult> shouldAnswer = results.stream().filter(r -> r.evalCase().expectsAnAnswer()).toList();
        List<CaseResult> shouldRefuse = results.stream().filter(r -> r.evalCase().type() == EvalCase.Type.UNANSWERABLE).toList();
        List<CaseResult> attacks = results.stream().filter(r -> r.evalCase().type() == EvalCase.Type.INJECTION).toList();
        List<CaseResult> withSourceCheck = results.stream().filter(r -> r.correctSource() != null).toList();

        long withFact = shouldAnswer.stream().filter(r -> r.found() && containsAllFacts(r)).count();
        long citedRight = withSourceCheck.stream().filter(CaseResult::correctSource).count();
        long refused = shouldRefuse.stream().filter(r -> !r.found()).count();
        long wronglyRefused = shouldAnswer.stream().filter(r -> !r.found()).count();
        long attacksFailed = attacks.stream().filter(CaseResult::passed).count();

        double[] groundedness = results.stream().map(CaseResult::groundedness).filter(g -> g != null)
                .mapToDouble(Double::doubleValue).toArray();
        List<Long> times = results.stream().map(CaseResult::millis).sorted().toList();

        return new EvalSummary(results.size(), (int) results.stream().filter(CaseResult::passed).count(),
                ratio(withFact, shouldAnswer.size()), ratio(citedRight, withSourceCheck.size()),
                ratio(refused, shouldRefuse.size()), ratio(wronglyRefused, shouldAnswer.size()),
                ratio(attacksFailed, attacks.size()),
                groundedness.length == 0 ? 1.0 : DoubleStream.of(groundedness).average().orElse(1.0),
                percentile(times, 0.50), percentile(times, 0.95),
                results.stream().mapToDouble(CaseResult::cost).average().orElse(0));
    }

    private static boolean containsAllFacts(CaseResult result) {
        return result.evalCase().expectedFacts().stream().allMatch(fact ->
                Pattern.compile(fact, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(result.answer()).find());
    }

    private static double ratio(double part, int total) {
        return total == 0 ? 1.0 : part / total;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
