package com.example.ragapp.resilience;

import static com.example.ragapp.TestPdfs.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Document processing while the embedding model has problems (a pretend Ollama, see FakeOllamaServer).
 *
 *   - a short hiccup  -> the batch is tried again and the document still ends COMPLETED,
 *                        without a single duplicate chunk (that is why a retry is safe here)
 *   - a real outage   -> after the tries the document ends FAILED with a clear message,
 *                        and no half-saved chunks are left behind
 */
@SpringBootTest(properties = {
        "app.resilience.ingestion.retry-attempts=3",
        "app.resilience.ingestion.retry-backoff=50ms",
        "app.rate-limit.uploads-per-hour-per-tenant=0"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionResilienceTest {

    private static final String TENANT = "test-ingest-resilience";
    private static FakeOllamaServer fakeOllama;

    @DynamicPropertySource
    static void useThePretendOllama(DynamicPropertyRegistry registry) throws Exception {
        fakeOllama = new FakeOllamaServer();
        registry.add("spring.ai.ollama.base-url", fakeOllama::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
        fakeOllama.close();
    }

    @Test
    void aShortHiccupOfTheEmbeddingModelIsRetriedWithoutDuplicates() throws Exception {
        fakeOllama.reset();
        fakeOllama.embeddingFailuresLeft.set(2);   // the first two requests fail, the third works

        JsonNode document = uploadAndWait("hiccup.pdf");

        assertThat(document.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(fakeOllama.embeddingRequests.get()).isGreaterThanOrEqualTo(3);   // 2 failures + the successful try
        Integer chunksInDatabase = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?", Integer.class,
                document.get("id").asText());
        assertThat(chunksInDatabase).isEqualTo(document.get("chunkCount").asInt());   // exactly one row per chunk
    }

    @Test
    void aRealOutageEndsWithAClearMessageAndNoLeftovers() throws Exception {
        fakeOllama.reset();
        fakeOllama.embeddingMode = FakeOllamaServer.Mode.ERROR;

        JsonNode document = uploadAndWait("outage.pdf");

        assertThat(document.get("status").asText()).isEqualTo("FAILED");
        assertThat(document.get("statusMessage").asText()).contains("embedding model is not available");
        assertThat(fakeOllama.embeddingRequests).hasValue(3);   // the 3 tries, then it gave up
        Integer chunksInDatabase = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?", Integer.class,
                document.get("id").asText());
        assertThat(chunksInDatabase).isZero();
    }

    // ----- helpers -----

    private JsonNode uploadAndWait(String fileName) throws Exception {
        byte[] file = pdf(List.of("Leave Policy", "Employees get 24 days of leave. " + UUID.randomUUID()));
        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", fileName, "application/pdf", file))
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        for (int attempt = 0; attempt < 200; attempt++) {
            JsonNode document = objectMapper.readTree(mockMvc.perform(get("/api/documents/" + id)
                    .header("X-Tenant-Id", TENANT)).andReturn().getResponse().getContentAsString());
            String state = document.get("status").asText();
            if (state.equals("COMPLETED") || state.equals("FAILED")) {
                return document;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Document " + id + " was not processed in time");
    }
}
