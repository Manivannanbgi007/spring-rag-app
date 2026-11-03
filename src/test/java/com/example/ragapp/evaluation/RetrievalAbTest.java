package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.document.DocumentService;
import com.example.ragapp.document.DocumentStatus;
import com.example.ragapp.document.DocumentStorage;
import com.example.ragapp.document.DocumentType;
import com.example.ragapp.document.PolicyDocument;
import com.example.ragapp.document.PolicyDocumentRepository;
import com.example.ragapp.ingestion.ChunkStore;
import com.example.ragapp.ingestion.IngestionProperties;
import com.example.ragapp.ingestion.IngestionService;
import com.example.ragapp.ingestion.parser.DocumentParser;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.retrieval.EmbeddingGateway;
import com.example.ragapp.retrieval.ChunkSearchRepository;
import com.example.ragapp.retrieval.RagProperties;
import com.example.ragapp.retrieval.Reranker;
import com.example.ragapp.retrieval.RetrievalService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * A/B TESTS of the search: the same questions, two or more variants of ONE setting, one table of results.
 * This is how a change (a new chunk size, a new threshold, another strategy) is judged with numbers
 * instead of feelings. Run it, read the table in the output, decide. Each test also asserts the one fact
 * that must stay true, so a real regression fails the build.
 *
 * To compare EMBEDDING MODELS: change EMBEDDING_MODEL in .env (and the vector size in application.yml and
 * the database), restart, upload again, run this test, and compare the "strategy" table with the one of the
 * old model. To compare PROMPTS see PromptAbTest.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalAbTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TENANT = "eval-ab-" + SUFFIX;
    private static final List<Integer> CHUNK_SIZES = List.of(30, 60, 500);

    @Autowired private DocumentService documentService;
    @Autowired private EmbeddingGateway embeddingGateway;
    @Autowired private ChunkSearchRepository searchRepository;
    @Autowired private PolicyDocumentRepository documentRepository;
    @Autowired private Reranker reranker;
    @Autowired private RagMetrics metrics;
    @Autowired private DocumentStorage storage;
    @Autowired private ChunkStore chunkStore;
    @Autowired private List<DocumentParser> parsers;
    @Autowired private JdbcTemplate jdbc;

    private final EvalCorpus corpus = EvalCorpus.load();
    private final EvalSet evalSet = EvalSet.load();
    private final List<EvalCase> answerable = evalSet.ofType(EvalCase.Type.ANSWERABLE);
    private final List<EvalCase> unanswerable = evalSet.ofType(EvalCase.Type.UNANSWERABLE);

    @BeforeAll
    void ingest() throws Exception {
        corpus.ingest(documentService, TENANT);
        for (int chunkSize : CHUNK_SIZES) {
            ingestWithChunkSize(chunkSize);
        }
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' LIKE ?", "eval-ab-" + SUFFIX + "%");
        jdbc.update("DELETE FROM documents WHERE tenant_id LIKE ?", "eval-ab-" + SUFFIX + "%");
    }

    // ===================================================================

    @Test
    void retrievalStrategies() {
        System.out.println("\nA/B: SEARCH STRATEGY (" + answerable.size() + " answerable questions)");
        RetrievalEvaluator.Result vectorOnly = run("meaning search only", false, false, 0.42, TENANT);
        run("meaning search + rerank", false, true, 0.42, TENANT);
        run("hybrid, no rerank", true, false, 0.42, TENANT);
        RetrievalEvaluator.Result full = run("hybrid + rerank (default)", true, true, 0.42, TENANT);

        assertThat(full.mrr()).as("the default strategy must not be worse than meaning search only")
                .isGreaterThanOrEqualTo(vectorOnly.mrr());
    }

    @Test
    void chunkSize() {
        System.out.println("\nA/B: CHUNK SIZE in tokens (default strategy)");
        for (int chunkSize : CHUNK_SIZES) {
            run("chunk size " + chunkSize, true, true, 0.42, TENANT + "-c" + chunkSize);
        }
        RetrievalEvaluator.Result baseline = run("chunk size 500 (default)", true, true, 0.42, TENANT);

        assertThat(baseline.hitAt5()).isGreaterThanOrEqualTo(QualityGates.MIN_HIT_AT_5);
    }

    @Test
    void similarityThreshold() {
        System.out.println("\nA/B: SIMILARITY THRESHOLD (default strategy). 'refused' = questions the documents cannot answer"
                + " for which the search returned NOTHING, so the model is not even asked (" + unanswerable.size() + " questions)");
        for (double threshold : new double[] {0.30, 0.35, 0.40, 0.42, 0.45, 0.50, 0.55, 0.60}) {
            RetrievalService service = service(true, true, threshold);
            RetrievalEvaluator.Result answerableResult = RetrievalEvaluator.evaluate(service, TENANT, answerable);
            long refused = unanswerable.stream()
                    .filter(c -> service.search(TENANT, c.question()).chunks().isEmpty()).count();
            System.out.printf("  threshold %.2f: answerable hit@5 %.2f | refused before the model %d of %d%n",
                    threshold, answerableResult.hitAt5(), refused, unanswerable.size());
        }
        // The reading of this table: a higher threshold refuses more unanswerable questions early, but at some
        // point it also loses answerable ones. The default (0.42) must keep every answerable question.
        assertThat(RetrievalEvaluator.evaluate(service(true, true, 0.42), TENANT, answerable).hitAt5())
                .isGreaterThanOrEqualTo(QualityGates.MIN_HIT_AT_5);
    }

    // ===================================================================

    private RetrievalEvaluator.Result run(String label, boolean hybrid, boolean rerank, double threshold, String tenant) {
        RetrievalEvaluator.Result result = RetrievalEvaluator.evaluate(service(hybrid, rerank, threshold), tenant, answerable);
        System.out.printf("  %-28s %s%n", label, result);
        return result;
    }

    private RetrievalService service(boolean hybrid, boolean rerank, double threshold) {
        RagProperties properties = new RagProperties(5, threshold, 8000, 300, hybrid, rerank, 20, 10, 60);
        return new RetrievalService(embeddingGateway, searchRepository, documentRepository, reranker, properties, metrics);
    }

    /**
     * Processes the corpus with a different chunk size, for tenant "<TENANT>-c<size>". The normal upload always
     * uses the configured size, so here the steps are done by hand with an IngestionService of its own.
     */
    private void ingestWithChunkSize(int chunkSize) {
        String tenant = TENANT + "-c" + chunkSize;
        UUID id = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", corpus.fileName(), "application/pdf", corpus.toPdf());

        PolicyDocument document = new PolicyDocument(id, tenant);
        document.setFileName(corpus.fileName());
        document.setTitle(corpus.title());
        document.setVersion(corpus.version());
        document.setContentType(DocumentType.PDF.getContentType());
        document.setFileSize(file.getSize());
        document.setFileHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        document.setStoragePath(storage.save(file, tenant, id, DocumentType.PDF));
        document.setStatus(DocumentStatus.QUEUED);
        documentRepository.saveAndFlush(document);

        // not created by Spring, so the @Async annotation does nothing and the processing runs right here
        new IngestionService(documentRepository, storage, chunkStore,
                new IngestionProperties(chunkSize, 32, 1, 1), metrics, parsers).ingestAsync(id);

        assertThat(documentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }
}
