package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.ragapp.chat.AnswerAttribution;
import com.example.ragapp.chat.ContextBuilder;
import com.example.ragapp.chat.PromptAssembler;
import com.example.ragapp.chat.RagAnswer;
import com.example.ragapp.chat.RagChatService;
import com.example.ragapp.document.DocumentService;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.privacy.PiiMasker;
import com.example.ragapp.resilience.ProtectedCalls;
import com.example.ragapp.retrieval.RagProperties;
import com.example.ragapp.retrieval.RetrievalService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A/B TEST OF THE SYSTEM PROMPT: the same questions answered with two different rule texts for the model.
 *   A = the real prompt (prompts/rag-system.st): use only the context, say "not found", ignore orders in documents
 *   B = a naive prompt: "answer using the context", nothing else
 * The surrounding protections (injection filter, answer check against the documents) stay switched on in both,
 * so the table shows what the PROMPT adds on top of them.
 *
 * Run with:   mvnw test -Pevaluation -Dtest=PromptAbTest
 */
@Tag("evaluation")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptAbTest {

    private static final String TENANT = "eval-prompts-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String NAIVE_PROMPT = "Answer the question using the context. Be short.";

    @Autowired private DocumentService documentService;
    @Autowired private RetrievalService retrievalService;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private PromptAssembler promptAssembler;
    @Autowired private AnswerAttribution attribution;
    @Autowired private PiiMasker piiMasker;
    @Autowired private ProtectedCalls protectedCalls;
    @Autowired private RagMetrics metrics;
    @Autowired private RagProperties ragProperties;
    @Autowired private com.example.ragapp.document.PolicyDocumentRepository documentRepository;
    @Autowired private ChatClient.Builder clientBuilder;
    @Autowired private JdbcTemplate jdbc;

    private final EvalCorpus corpus = EvalCorpus.load();
    private final EvalSet evalSet = EvalSet.load();

    @BeforeAll
    void ingest() throws Exception {
        corpus.ingest(documentService, TENANT);
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
    }

    @Test
    void theRealPromptIsNotWorseThanTheNaiveOne() throws Exception {
        List<EvalCase> answerable = evalSet.ofType(EvalCase.Type.ANSWERABLE).subList(0, 12);
        List<EvalCase> unanswerable = evalSet.ofType(EvalCase.Type.UNANSWERABLE);

        Resource realPrompt = new ClassPathResource("prompts/rag-system.st");
        String real = new String(realPrompt.getContentAsByteArray());

        Score a = score("A: real prompt", real, answerable, unanswerable);
        Score b = score("B: naive prompt", NAIVE_PROMPT, answerable, unanswerable);

        System.out.println("\nA/B: SYSTEM PROMPT (" + answerable.size() + " answerable, " + unanswerable.size() + " unanswerable questions)");
        System.out.println("  " + a);
        System.out.println("  " + b);

        assertThat(a.factRecall() + a.correctRefusals())
                .as("the real prompt must not be worse than the naive prompt").isGreaterThanOrEqualTo(b.factRecall() + b.correctRefusals() - 0.05);
        assertThat(a.correctRefusals()).isGreaterThanOrEqualTo(QualityGates.MIN_CORRECT_REFUSALS);
    }

    record Score(String label, double factRecall, double correctRefusals) {
        @Override
        public String toString() {
            return String.format("%-18s fact recall %.0f%% | correct refusals %.0f%%", label, factRecall * 100, correctRefusals * 100);
        }
    }

    private Score score(String label, String systemPrompt, List<EvalCase> answerable, List<EvalCase> unanswerable) {
        // text is used as it is: it has no {braces}, so it is safe as a template too
        ChatClient client = clientBuilder.defaultSystem(systemPrompt).build();
        RagChatService service = new RagChatService(retrievalService, contextBuilder, promptAssembler, attribution,
                piiMasker, protectedCalls, metrics, client, ragProperties, documentRepository);

        int factsRight = 0;
        for (EvalCase c : answerable) {
            RagAnswer answer = service.answer(TENANT, c.question());
            boolean allFacts = answer.found() && c.expectedFacts().stream().allMatch(fact ->
                    Pattern.compile(fact, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(answer.answer()).find());
            factsRight += allFacts ? 1 : 0;
        }
        int refused = 0;
        for (EvalCase c : unanswerable) {
            refused += service.answer(TENANT, c.question()).found() ? 0 : 1;
        }
        return new Score(label, (double) factsRight / answerable.size(), (double) refused / unanswerable.size());
    }
}
