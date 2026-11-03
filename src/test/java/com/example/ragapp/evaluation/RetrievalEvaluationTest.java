package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.document.DocumentService;
import com.example.ragapp.retrieval.RetrievalService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RETRIEVAL EVALUATION: does the search find the right page? (No language model is used, so this is fast and
 * runs with every "mvnw test".) The numbers must reach the quality gates in QualityGates.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvaluationTest {

    private static final String TENANT = "eval-retrieval-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private DocumentService documentService;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private JdbcTemplate jdbc;

    private final EvalCorpus corpus = EvalCorpus.load();
    private final EvalSet evalSet = EvalSet.load();

    @BeforeAll
    void ingestCorpus() throws Exception {
        corpus.ingest(documentService, TENANT);
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
    }

    @Test
    void theSearchFindsTheRightPageForEveryAnswerableQuestion() {
        List<EvalCase> questions = evalSet.ofType(EvalCase.Type.ANSWERABLE);

        RetrievalEvaluator.Result result = RetrievalEvaluator.evaluate(retrievalService, TENANT, questions);

        System.out.println("RETRIEVAL " + result + (result.misses().isEmpty() ? "" : "  not first: " + result.misses()));
        assertThat(QualityGates.violationsOfSearch(result)).as("quality gates for the search: " + result).isEmpty();
    }

    @Test
    void theCorpusHasTheExpectedNumberOfQuestions() {
        // guards the dataset itself: a typo in eval-set.json must not silently shrink the evaluation
        assertThat(evalSet.ofType(EvalCase.Type.ANSWERABLE)).hasSizeGreaterThanOrEqualTo(20);
        assertThat(evalSet.ofType(EvalCase.Type.UNANSWERABLE)).hasSizeGreaterThanOrEqualTo(8);
        assertThat(evalSet.ofType(EvalCase.Type.FOLLOW_UP)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(evalSet.ofType(EvalCase.Type.INJECTION)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(evalSet.ofType(EvalCase.Type.PII)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(evalSet.cases().stream().map(EvalCase::id).distinct().count()).isEqualTo(evalSet.cases().size());
        evalSet.cases().stream().filter(c -> c.expectedPage() != null)
                .forEach(c -> assertThat(c.expectedPage()).as(c.id()).isBetween(1, corpus.pages().size()));
    }
}
