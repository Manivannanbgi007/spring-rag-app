package com.example.ragapp.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What happens when the models fail? The application runs for real (real database, real Spring AI),
 * but the models are a pretend Ollama (FakeOllamaServer) that is switched between "fine", "broken"
 * and "slow". The tests tell one story, in order:
 *
 *   1. everything works                      -> normal answer
 *   2. the language model breaks             -> retries, then the closest passages instead of an answer
 *   3. it keeps failing                      -> circuit opens: no more calls to the broken model, instant reply
 *   4. the embedding model breaks too        -> search continues with keywords only
 *   5. both come back                        -> circuit tests the model again and closes: normal answers
 *   6. the language model becomes very slow  -> timeout, passages instead of waiting for ever
 *
 * Limits are made small here (1 second timeouts, 1 second open time) so the story runs in seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.resilience.llm.timeout=1s",
        "app.resilience.llm.stream-idle-timeout=1s",
        "app.resilience.llm.retry-attempts=2",
        "app.resilience.llm.retry-backoff=50ms",
        "app.resilience.llm.minimum-calls=4",
        "app.resilience.llm.open-duration=1s",
        "app.resilience.embedding.timeout=1s",
        "app.resilience.embedding.retry-attempts=2",
        "app.resilience.embedding.retry-backoff=50ms",
        "app.resilience.embedding.minimum-calls=4",
        "app.resilience.embedding.open-duration=1s",
        "app.rate-limit.user-per-minute=0",
        "app.rate-limit.tenant-per-minute=0"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelOutageTest {

    private static final String TENANT = "test-outage-tenant";
    private static FakeOllamaServer fakeOllama;

    @DynamicPropertySource
    static void useThePretendOllama(DynamicPropertyRegistry registry) throws Exception {
        fakeOllama = new FakeOllamaServer();
        registry.add("spring.ai.ollama.base-url", fakeOllama::baseUrl);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProtectedCalls protectedCalls;

    private final HttpClient http = HttpClient.newHttpClient();

    // ----- test data: two chunks that are searchable by keyword, without needing a real embedding model -----

    @BeforeAll
    void createTestDocument() {
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (id, tenant_id, file_name, title, version, content_type, file_size, file_hash,
                                       storage_path, status, page_count, chunk_count)
                VALUES (?, ?, 'handbook.pdf', 'Handbook', '1.0', 'application/pdf', 1000, ?, 'none', 'COMPLETED', 2, 2)
                """, documentId, TENANT, "b".repeat(64));

        String sameVector = "[" + "0.01,".repeat(767) + "0.01]";
        insertChunk(documentId, "All full-time employees are entitled to 24 days of paid annual leave per calendar year.", 1, sameVector);
        insertChunk(documentId, "Employees receive 10 days of paid sick leave per year.", 2, sameVector);
    }

    private void insertChunk(UUID documentId, String text, int page, String vector) {
        String metadata = "{\"document_id\":\"" + documentId + "\",\"tenant_id\":\"" + TENANT + "\","
                + "\"file_name\":\"handbook.pdf\",\"title\":\"Handbook\",\"version\":\"1.0\",\"page_number\":" + page + "}";
        jdbc.update("INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                UUID.randomUUID(), text, metadata, vector);
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM usage_records WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
        fakeOllama.close();
    }

    // ----- the story -----

    @Test
    @Order(1)
    void everythingWorks() throws Exception {
        fakeOllama.reset();

        JsonNode first = ask("How many days of annual leave do employees get?");
        JsonNode second = ask("How many days of annual leave do employees get?");   // the same question again

        assertThat(first.get("found").asBoolean()).isTrue();
        assertThat(first.get("degraded").asBoolean()).isFalse();
        assertThat(first.get("answer").asText()).contains("24 days");
        assertThat(second.get("answer").asText()).contains("24 days");
        assertThat(fakeOllama.embeddingRequests).hasValue(1);   // the second time the vector came from the cache
        assertThat(protectedCalls.llm().circuitState()).isEqualTo("CLOSED");
    }

    @Test
    @Order(2)
    void whenTheLanguageModelBreaksTheUserGetsTheClosestPassagesInstead() throws Exception {
        fakeOllama.reset();
        fakeOllama.chatMode = FakeOllamaServer.Mode.ERROR;
        long start = System.nanoTime();

        JsonNode reply = ask("How many paid days of annual leave exist?");

        assertThat(reply.get("degraded").asBoolean()).isTrue();
        assertThat(reply.get("found").asBoolean()).isTrue();
        assertThat(reply.get("answer").asText()).startsWith("The language model is not available right now");
        assertThat(reply.get("answer").asText()).contains("24 days of paid annual leave");
        assertThat(reply.get("sources")).isNotEmpty();
        assertThat(reply.get("sources").get(0).get("pageNumber").asInt()).isEqualTo(1);
        assertThat(fakeOllama.chatRequests).hasValue(2);   // exactly our 2 tries: no hidden retries below us
        // two quick errors and a short pause, not a timeout: the errors were seen, not waited out
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1800));
    }

    @Test
    @Order(3)
    void whenItKeepsFailingTheCircuitOpensAndTheModelIsLeftAlone() throws Exception {
        fakeOllama.reset();
        fakeOllama.chatMode = FakeOllamaServer.Mode.ERROR;

        for (int i = 0; i < 3; i++) {
            ask("What is the number " + i + " of annual leave days per employee?");
        }
        assertThat(protectedCalls.llm().circuitState()).isEqualTo("OPEN");

        int before = fakeOllama.chatRequests.get();
        long start = System.nanoTime();
        JsonNode reply = ask("How many annual leave days does a full-time employee have?");

        assertThat(reply.get("degraded").asBoolean()).isTrue();
        assertThat(reply.get("answer").asText()).startsWith("The language model is not available right now");
        assertThat(fakeOllama.chatRequests).hasValue(before);   // the broken model was not called again
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @Order(4)
    void whenTheEmbeddingModelBreaksTooTheSearchContinuesWithKeywords() throws Exception {
        fakeOllama.reset();
        fakeOllama.embeddingMode = FakeOllamaServer.Mode.ERROR;
        fakeOllama.chatMode = FakeOllamaServer.Mode.ERROR;

        JsonNode reply = ask("Tell me about sick leave days");   // a new question: not in the embedding cache

        assertThat(reply.get("degraded").asBoolean()).isTrue();
        assertThat(reply.get("found").asBoolean()).isTrue();
        assertThat(reply.get("answer").asText()).contains("10 days of paid sick leave");
        assertThat(reply.get("sources").get(0).get("pageNumber").asInt()).isEqualTo(2);
        assertThat(fakeOllama.embeddingRequests.get()).isGreaterThanOrEqualTo(1);   // it was tried
    }

    @Test
    @Order(5)
    void whenTheModelsComeBackTheCircuitClosesAgain() throws Exception {
        fakeOllama.reset();   // both models are fine again
        Thread.sleep(1200);   // longer than the 1 second the circuit stays open

        // The circuit lets two test calls through; when they succeed it closes.
        JsonNode one = ask("What are the paid annual leave days for employees per year?");
        JsonNode two = ask("What are the paid sick leave days for employees per year?");
        JsonNode three = ask("How many days of annual leave are granted to employees?");

        assertThat(one.get("degraded").asBoolean()).isFalse();
        assertThat(two.get("degraded").asBoolean()).isFalse();
        assertThat(three.get("degraded").asBoolean()).isFalse();
        assertThat(three.get("answer").asText()).contains("24 days");
        assertThat(protectedCalls.llm().circuitState()).isEqualTo("CLOSED");
        assertThat(protectedCalls.embedding().circuitState()).isEqualTo("CLOSED");
    }

    @Test
    @Order(6)
    void aSlowLanguageModelIsNotWaitedForForEver() throws Exception {
        fakeOllama.reset();
        fakeOllama.chatMode = FakeOllamaServer.Mode.SLOW;   // answers after 4 seconds, the limit is 1 second
        long start = System.nanoTime();

        JsonNode reply = ask("Please state the annual leave entitlement of employees");

        long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertThat(reply.get("degraded").asBoolean()).isTrue();
        assertThat(reply.get("answer").asText()).startsWith("The language model is not available right now");
        assertThat(seconds).isLessThan(4);   // 2 tries of 1 second plus the pause, not 4 seconds or more
    }

    // ----- streaming -----

    @Test
    @Order(7)
    void streamedAnswerWorksAndFallsBackToPassagesWhenTheModelIsDown() throws Exception {
        fakeOllama.reset();
        protectedCalls.llm();   // (the circuit may still be open from the slow model: wait for it to test again)
        Thread.sleep(1200);

        String healthy = streamRaw("How many days of annual leave can employees take in total?");
        assertThat(healthy).contains("event:token").contains("event:done");
        assertThat(healthy).contains("\"degraded\":false");

        fakeOllama.chatMode = FakeOllamaServer.Mode.ERROR;
        String broken = streamRaw("How many days of paid annual leave are there for staff?");
        assertThat(broken).contains("event:done");
        assertThat(broken).contains("\"degraded\":true");
        assertThat(broken).contains("The language model is not available right now");
        assertThat(broken).doesNotContain("event:error");
    }

    @Test
    @Order(8)
    void healthShowsTheStateOfTheCircuitBreakers() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode resilience = objectMapper.readTree(response.body()).path("components").path("resilience");
        assertThat(resilience.path("status").asText()).isEqualTo("UP");
        assertThat(resilience.path("details").path("llm").path("circuit").asText()).isIn("CLOSED", "OPEN", "HALF_OPEN");
        assertThat(resilience.path("details").path("embedding").path("freeSlots").asInt()).isPositive();
    }

    // ----- helpers -----

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode ask(String question) throws Exception {
        HttpResponse<String> response = post("/api/chat", "{\"question\":\"" + question + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private String streamRaw(String question) throws Exception {
        HttpResponse<String> response = post("/api/chat/stream", "{\"question\":\"" + question + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT)
                .header("X-User-Id", "outage-tester")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
