package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.ragapp.chat.ChatService;
import com.example.ragapp.common.SourceReference;
import com.example.ragapp.document.DocumentService;
import com.example.ragapp.evaluation.AnswerEvaluator.CaseResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ANSWER EVALUATION: every question of eval-set.json goes through the whole system with the real language model.
 * Slow (a few minutes on a CPU), so it is not part of the normal "mvnw test". Run it with:
 *
 *     mvnw test -Pevaluation -Dtest=AnswerEvaluationTest
 *
 * Needs the Docker stack. The result is printed and written to target/evaluation/answers.md.
 * The build fails when a number is below its gate (QualityGates).
 */
@Tag("evaluation")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswerEvaluationTest {

    private static final String TENANT = "eval-answers-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired private DocumentService documentService;
    @Autowired private ChatService chatService;
    @Autowired private GroundednessChecker groundednessChecker;
    @Autowired @Qualifier("rewriteChatClient") private ChatClient judgeClient;
    @Autowired private JdbcTemplate jdbc;
    @Value("${spring.ai.ollama.chat.options.model}") private String model;

    private final EvalCorpus corpus = EvalCorpus.load();
    private final EvalSet evalSet = EvalSet.load();

    @BeforeAll
    void ingestCorpus() throws Exception {
        corpus.ingest(documentService, TENANT);
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM usage_records WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
    }

    @Test
    void theWholeSystemReachesTheQualityGates() {
        AnswerEvaluator evaluator = new AnswerEvaluator(chatService, groundednessChecker, corpus);

        // -Deval.cases=F01,F02 runs only these questions (to investigate a failure quickly)
        String only = System.getProperty("eval.cases", "");
        List<EvalCase> cases = only.isBlank() ? evalSet.cases()
                : evalSet.cases().stream().filter(c -> List.of(only.split(",")).contains(c.id())).toList();

        List<CaseResult> results = evaluator.run(TENANT, cases);
        EvalSummary summary = EvalSummary.of(results);
        List<String> violations = QualityGates.violationsOfAnswers(summary);

        Path report = EvalReport.write("answers", model, summary, results, violations, judgeSection(results));

        System.out.println("\nANSWER EVALUATION: " + summary.passed() + " of " + summary.questions() + " questions passed. " + summary);
        results.stream().filter(r -> !r.passed()).forEach(r ->
                System.out.println("  FAILED " + r.evalCase().id() + ": " + String.join("; ", r.problems())));
        System.out.println("Report: " + report.toAbsolutePath());

        assertThat(violations).as("quality gates (details in " + report + ")").isEmpty();
    }

    /** The judge model as a second opinion on the groundedness of the written answers. Informational, not a gate. */
    private String judgeSection(List<CaseResult> results) {
        LlmJudge judge = new LlmJudge(judgeClient);
        int judged = 0;
        int supportedByJudge = 0;
        List<String> disagreements = new ArrayList<>();

        for (CaseResult result : results) {
            if (result.groundedness() == null) {
                continue;
            }
            String evidence = result.sources().stream().map(SourceReference::pageNumber).distinct()
                    .map(corpus::pageText).reduce("", (a, b) -> a + "\n" + b);
            Boolean verdict = judge.isSupported(result.evalCase().question(), evidence, result.answer());
            if (verdict == null) {
                continue;
            }
            judged++;
            supportedByJudge += verdict ? 1 : 0;
            boolean lexicalSaysSupported = result.groundedness() >= 0.99;
            if (verdict != lexicalSaysSupported) {
                disagreements.add(result.evalCase().id() + " (judge: " + (verdict ? "supported" : "unsupported")
                        + ", word check: " + EvalReport.pct(result.groundedness()) + ")");
            }
        }
        return "\n## Second opinion of the judge model (not a gate)\n\n"
                + "The judge found " + supportedByJudge + " of " + judged + " written answers fully supported by the cited pages.\n"
                + (disagreements.isEmpty() ? "It agrees with the word check on every answer.\n"
                : "It disagrees with the word check on: " + String.join(", ", disagreements) + "\n");
    }
}
