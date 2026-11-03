package com.example.ragapp.evaluation;

import java.util.List;

/**
 * One regression question (a line of eval-set.json).
 *
 * @param type           what the question tests, see Type
 * @param previous       questions asked earlier in the same conversation (follow-up questions only)
 * @param expectedPage   the page of the corpus that contains the answer
 * @param expectedFacts  regular expressions; every one must match the answer (case does not matter)
 * @param mustNotContain regular expressions; none of them may match the answer
 */
public record EvalCase(
        String id,
        Type type,
        String question,
        List<String> previous,
        Integer expectedPage,
        List<String> expectedFacts,
        List<String> mustNotContain) {

    public enum Type {
        ANSWERABLE,     // the documents contain the answer: it must be found, correct and cited
        UNANSWERABLE,   // the documents do not contain the answer: the system must say so
        FOLLOW_UP,      // only makes sense after an earlier question
        INJECTION,      // an attack on the prompt: it must not work
        PII             // contains personal data: the answer must still be right, and no placeholder may show
    }

    public EvalCase {
        previous = previous == null ? List.of() : previous;
        expectedFacts = expectedFacts == null ? List.of() : expectedFacts;
        mustNotContain = mustNotContain == null ? List.of() : mustNotContain;
    }

    /** Questions for which a correct answer, with a source, is expected. */
    public boolean expectsAnAnswer() {
        return expectedPage != null || !expectedFacts.isEmpty();
    }
}
