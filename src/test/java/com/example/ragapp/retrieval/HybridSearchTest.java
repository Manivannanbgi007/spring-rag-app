package com.example.ragapp.retrieval;

import static com.example.ragapp.TestPdfs.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.ragapp.document.PolicyDocumentRepository;
import com.example.ragapp.observability.RagMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Hybrid search against the real database and the real embedding model.
 * Needs the Docker stack:  docker compose up -d
 *
 * Two test documents: a small policy (with a made-up form code ZX-4471) and a catalogue of 40 almost
 * identical form pages (AB-1001 ... AB-1040). In measurements the meaning search alone already finds
 * most codes (8 of 10 first), because the embedding model reads numbers well. The keyword search makes
 * it exact (10 of 10) and does not depend on that luck.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchTest {

    private static final String TENANT = "test-hybrid-tenant";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmbeddingGateway embeddingGateway;
    @Autowired
    private ChunkSearchRepository searchRepository;
    @Autowired
    private PolicyDocumentRepository documentRepository;
    @Autowired
    private Reranker reranker;
    @Autowired
    private RagMetrics metrics;

    private static final int FORM_PAGES = 40;

    private String documentId;
    private String formsDocumentId;

    @BeforeAll
    void uploadDocument() throws Exception {
        byte[] file = pdf(
                List.of("Leave Policy", "All full-time employees are entitled to 24 days of paid annual leave per calendar year.",
                        "Up to 5 unused days may be carried forward to the next year."),
                List.of("Special Arrangements", "Employees who want an unpaid career break must submit form ZX-4471 to HR.",
                        "The request needs the approval of the department head."),
                List.of("Travel", "Business travel must be booked through the travel portal.",
                        "Hotel costs are reimbursed up to 150 USD per night."));

        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "policies.pdf", "application/pdf", file))
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        documentId = objectMapper.readTree(body).get("id").asText();
        waitUntilCompleted(documentId);

        formsDocumentId = upload("forms.pdf", formsCatalogue());
        waitUntilCompleted(formsDocumentId);
    }

    /**
     * 40 pages that look almost the same and differ mainly by a form code (AB-1001 ... AB-1040).
     * Like a real catalogue of HR forms. Meaning search has a hard time telling such pages apart.
     */
    @SuppressWarnings("unchecked")
    private byte[] formsCatalogue() throws Exception {
        String[] purposes = {"external training", "a new laptop", "a parking permit", "remote work", "a name change",
                "an address change", "bank detail changes", "a travel advance", "a reference letter", "unpaid leave",
                "a flexible schedule", "overtime approval", "a transfer", "a promotion review", "an expense claim",
                "a medical reimbursement", "a visa letter", "equipment return", "a mentoring programme", "a conference"};
        List<String>[] pages = new List[FORM_PAGES];
        for (int i = 0; i < FORM_PAGES; i++) {
            pages[i] = List.of("Form AB-" + (1001 + i),
                    "Employees use this form to request " + purposes[i % purposes.length] + ".",
                    "Submit the completed form to HR. The department head must approve the request.",
                    "Processing takes about " + (3 + i % 5) + " working days.");
        }
        return pdf(pages);
    }

    private String upload(String fileName, byte[] file) throws Exception {
        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", fileName, "application/pdf", file))
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void waitUntilCompleted(String id) throws Exception {
        for (int attempt = 0; attempt < 240; attempt++) {
            String state = objectMapper.readTree(mockMvc.perform(get("/api/documents/" + id)
                            .header("X-Tenant-Id", TENANT)).andReturn().getResponse().getContentAsString())
                    .get("status").asText();
            if (state.equals("COMPLETED")) {
                return;
            }
            assertThat(state).isNotEqualTo("FAILED");
            Thread.sleep(500);
        }
        throw new AssertionError("Document " + id + " was not processed in time");
    }

    @AfterAll
    void deleteDocument() throws Exception {
        mockMvc.perform(delete("/api/documents/" + documentId).header("X-Tenant-Id", TENANT));
        mockMvc.perform(delete("/api/documents/" + formsDocumentId).header("X-Tenant-Id", TENANT));
    }

    @Test
    void rightFormIsFirstForEveryCodeWhenHybridSearchIsOn() {
        // 40 pages that look almost the same: only the code tells them apart
        for (int page : new int[] {3, 7, 12, 18, 21, 26, 30, 33, 37, 40}) {
            for (boolean rerank : new boolean[] {true, false}) {
                List<RetrievedChunk> found = service(true, rerank).search(TENANT, "What is form AB-" + (1000 + page) + "?");

                assertThat(found).as("page %s, rerank=%s", page, rerank).isNotEmpty();
                assertThat(found.get(0).fileName()).isEqualTo("forms.pdf");
                assertThat(found.get(0).pageNumber()).as("page %s, rerank=%s", page, rerank).isEqualTo(page);
            }
        }
    }

    @Test
    void hybridSearchIsNeverWorseThanVectorSearchAlone() {
        int vectorOnly = 0;
        int hybrid = 0;
        for (int page : new int[] {3, 7, 12, 18, 21, 26, 30, 33, 37, 40}) {
            String question = "What is form AB-" + (1000 + page) + "?";
            vectorOnly += firstPageIs(service(false, false).search(TENANT, question), page);
            hybrid += firstPageIs(service(true, false).search(TENANT, question), page);
        }
        assertThat(hybrid).isGreaterThanOrEqualTo(vectorOnly);
    }

    private int firstPageIs(List<RetrievedChunk> found, int page) {
        return !found.isEmpty() && found.get(0).pageNumber() == page ? 1 : 0;
    }

    @Test
    void findsThePageThatContainsTheCode() {
        List<RetrievedChunk> hybrid = service(true, true).search(TENANT, "What is form ZX-4471?");

        assertThat(hybrid).isNotEmpty();
        assertThat(hybrid.get(0).pageNumber()).isEqualTo(2);
        assertThat(hybrid.get(0).text()).contains("ZX-4471");
    }

    @Test
    void aCodeAloneIsEnough() {
        List<RetrievedChunk> hybrid = service(true, true).search(TENANT, "ZX-4471");

        assertThat(hybrid).isNotEmpty();
        assertThat(hybrid.get(0).pageNumber()).isEqualTo(2);
    }

    @Test
    void normalQuestionsStillWork() {
        for (boolean hybrid : new boolean[] {true, false}) {
            for (boolean rerank : new boolean[] {true, false}) {
                List<RetrievedChunk> chunks = service(hybrid, rerank).search(TENANT, "How many days of annual leave do I get?");

                assertThat(chunks).as("hybrid=%s rerank=%s", hybrid, rerank).isNotEmpty();
                assertThat(chunks.get(0).pageNumber()).as("hybrid=%s rerank=%s", hybrid, rerank).isEqualTo(1);
            }
        }
    }

    @Test
    void offTopicQuestionStillFindsNothing() {
        assertThat(service(true, true).search(TENANT, "What is the capital of France?")).isEmpty();
        assertThat(service(true, true).search(TENANT, "How do I cook pasta?")).isEmpty();
    }

    @Test
    void anotherTenantFindsNothingEvenWithTheExactCode() {
        assertThat(service(true, true).search("some-other-tenant", "ZX-4471")).isEmpty();
        assertThat(service(true, true).search("some-other-tenant", "annual leave days")).isEmpty();
    }

    @Test
    void symbolsInTheQuestionCannotBreakTheKeywordSearch() {
        List<String> nastyQuestions = List.of(
                "'; DROP TABLE vector_store; --",
                "leave & | ! ( ) :* <-> \\ \"",
                "!!!",
                "a",
                "ZX-4471 OR 1=1",
                "\u0000 leave");

        for (String question : nastyQuestions) {
            // must not throw; what is found does not matter here
            service(true, true).search(TENANT, question.replace("\u0000", ""));
        }
        assertThat(documentRepository.count()).isGreaterThanOrEqualTo(1);   // the table still exists
    }

    // ----- helpers -----

    /** The search of a RetrievalService with the given switches, built from the real beans. */
    private Searcher service(boolean hybrid, boolean rerank) {
        RagProperties properties = new RagProperties(5, 0.42, 8000, 300, hybrid, rerank, 20, 10, 60);
        RetrievalService retrieval = new RetrievalService(embeddingGateway, searchRepository, documentRepository,
                reranker, properties, metrics);
        return (tenant, question) -> retrieval.search(tenant, question).chunks();
    }

    @FunctionalInterface
    private interface Searcher {
        List<RetrievedChunk> search(String tenant, String question);
    }
}
