package com.example.ragapp.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.ragapp.chat.ChatRequest;
import com.example.ragapp.chat.ChatResponse;
import com.example.ragapp.chat.ChatService;
import com.example.ragapp.common.Caller;
import com.example.ragapp.common.SourceReference;

/**
 * Runs the questions through the whole system (search, model, checks, citations) and decides for every
 * question whether the reply is acceptable. Slow, because the language model really answers.
 */
public class AnswerEvaluator {

    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private final ChatService chatService;
    private final GroundednessChecker groundednessChecker;
    private final EvalCorpus corpus;

    public AnswerEvaluator(ChatService chatService, GroundednessChecker groundednessChecker, EvalCorpus corpus) {
        this.chatService = chatService;
        this.groundednessChecker = groundednessChecker;
        this.corpus = corpus;
    }

    /**
     * What happened for one question.
     *
     * @param problems        what was wrong; empty when the reply was acceptable
     * @param groundedness    share of the claims in the answer that the cited pages support (null when it does not apply)
     * @param correctSource   true/false when the question has an expected page and was answered; null otherwise
     */
    public record CaseResult(EvalCase evalCase, String answer, boolean found, boolean degraded,
                             List<SourceReference> sources, long millis, double cost, Double groundedness,
                             Boolean correctSource, List<String> problems) {

        public boolean passed() {
            return problems.isEmpty();
        }
    }

    public List<CaseResult> run(String tenantId, List<EvalCase> cases) {
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            results.add(runOne(tenantId, evalCase));
        }
        return results;
    }

    public CaseResult runOne(String tenantId, EvalCase evalCase) {
        Caller caller = new Caller(tenantId, "eval-" + evalCase.id().toLowerCase());

        // earlier questions of the conversation (follow-up questions)
        UUID conversationId = null;
        for (String earlier : evalCase.previous()) {
            conversationId = chatService.ask(caller, new ChatRequest(earlier, conversationId)).conversationId();
        }

        long start = System.nanoTime();
        ChatResponse response = chatService.ask(caller, new ChatRequest(evalCase.question(), conversationId));
        long millis = (System.nanoTime() - start) / 1_000_000;

        List<String> problems = new ArrayList<>();
        Boolean correctSource = judge(evalCase, response, problems);
        Double groundedness = groundedness(response);
        if (!problems.isEmpty() && evalCase.type() == EvalCase.Type.FOLLOW_UP) {
            problems.add("the follow-up was understood as: " + response.standaloneQuestion());
        }

        return new CaseResult(evalCase, response.answer(), response.found(), response.degraded(), response.sources(),
                millis, response.usage().estimatedCostUsd(), groundedness, correctSource, problems);
    }

    /** Applies the rules of the question type. Adds a text to "problems" for every rule that is broken. */
    private Boolean judge(EvalCase evalCase, ChatResponse response, List<String> problems) {
        String answer = response.answer();
        Boolean correctSource = null;

        if (evalCase.type() == EvalCase.Type.UNANSWERABLE) {
            if (response.found()) {
                problems.add("should have said it cannot find the answer, but answered: " + shorten(answer));
            }
        } else if (evalCase.expectsAnAnswer()) {
            if (!response.found()) {
                problems.add("refused to answer although the documents contain the answer");
            }
            for (String fact : evalCase.expectedFacts()) {
                if (!Pattern.compile(fact, REGEX_FLAGS).matcher(answer).find()) {
                    problems.add("the answer does not contain the expected fact /" + fact + "/: " + shorten(answer));
                }
            }
            if (response.found() && evalCase.expectedPage() != null) {
                correctSource = response.sources().stream()
                        .anyMatch(source -> evalCase.expectedPage().equals(source.pageNumber()));
                if (!correctSource) {
                    problems.add("none of the sources is page " + evalCase.expectedPage()
                            + " (sources: " + response.sources().stream().map(SourceReference::pageNumber).toList() + ")");
                }
            }
        }

        for (String forbidden : evalCase.mustNotContain()) {
            if (Pattern.compile(forbidden, REGEX_FLAGS).matcher(answer).find()) {
                problems.add("the answer contains something that must not appear: /" + forbidden + "/");
            }
        }
        return correctSource;
    }

    /** Groundedness of a written answer, against the full text of the pages it cites. */
    private Double groundedness(ChatResponse response) {
        if (!response.found() || response.degraded() || response.sources().isEmpty()) {
            return null;
        }
        List<String> evidence = response.sources().stream()
                .map(SourceReference::pageNumber)
                .filter(page -> page != null)
                .distinct()
                .map(corpus::pageText)
                .toList();
        return groundednessChecker.check(response.answer(), evidence).score();
    }

    private String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 110 ? oneLine : oneLine.substring(0, 110) + "...";
    }
}
