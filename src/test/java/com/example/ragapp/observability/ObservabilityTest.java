package com.example.ragapp.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragapp.resilience.FakeOllamaServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Metrics, token and cost tracking, the usage report, and privacy of the telemetry.
 * Uses a pretend Ollama that reports fixed token counts (7 embedding, 120 prompt, 15 completion), so the
 * numbers can be checked exactly. Prices are set to simple values so the cost can be calculated by hand:
 *     120/1000 * 1.0  +  15/1000 * 2.0  +  7/1000 * 0.5  =  0.12 + 0.03 + 0.0035  =  0.1535 dollars
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.cost.llm-input-per-1k=1.0",
        "app.cost.llm-output-per-1k=2.0",
        "app.cost.embedding-per-1k=0.5",
        "app.rate-limit.user-per-minute=0",
        "app.rate-limit.tenant-per-minute=0",
        "app.resilience.llm.retry-attempts=1",
        "app.resilience.llm.retry-backoff=20ms"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityTest {

    private static final String TENANT = "test-obs-tenant";
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

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeAll
    void createTestDocumentAndStartLogCapture() {
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (id, tenant_id, file_name, title, version, content_type, file_size, file_hash,
                                       storage_path, status, page_count, chunk_count)
                VALUES (?, ?, 'handbook.pdf', 'Handbook', '1.0', 'application/pdf', 1000, ?, 'none', 'COMPLETED', 1, 1)
                """, documentId, TENANT, "c".repeat(64));
        String metadata = "{\"document_id\":\"" + documentId + "\",\"tenant_id\":\"" + TENANT + "\","
                + "\"file_name\":\"handbook.pdf\",\"title\":\"Handbook\",\"version\":\"1.0\",\"page_number\":1}";
        jdbc.update("INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                UUID.randomUUID(), "All full-time employees are entitled to 24 days of paid annual leave per calendar year.",
                metadata, "[" + "0.01,".repeat(767) + "0.01]");

        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterAll
    void cleanUp() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        jdbc.update("DELETE FROM conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM usage_records WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
        fakeOllama.close();
    }

    // ----- tokens and cost -----

    @Test
    @Order(1)
    void aQuestionReportsItsTokensAndEstimatedCost() throws Exception {
        JsonNode answer = ask("How many days of annual leave do employees get per year?");

        assertThat(answer.get("found").asBoolean()).isTrue();
        JsonNode usage = answer.get("usage");
        assertThat(usage.get("embeddingTokens").asInt()).isEqualTo(7);
        assertThat(usage.get("promptTokens").asInt()).isEqualTo(120);
        assertThat(usage.get("completionTokens").asInt()).isEqualTo(15);
        assertThat(usage.get("estimatedCostUsd").asDouble()).isCloseTo(0.1535, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @Order(2)
    void aRepeatedQuestionCostsNoEmbeddingTokensBecauseTheVectorIsCached() throws Exception {
        String question = "How many days of annual leave do employees get per year?";   // asked in test 1 already

        JsonNode usage = ask(question).get("usage");

        assertThat(usage.get("embeddingTokens").asInt()).isZero();
        assertThat(usage.get("promptTokens").asInt()).isEqualTo(120);
        assertThat(usage.get("estimatedCostUsd").asDouble()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @Order(3)
    void theUsageReportAddsUpThePerTenantNumbers() throws Exception {
        HttpResponse<String> response = get("/api/usage?days=7");
        JsonNode totals = objectMapper.readTree(response.body()).get("totals");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(totals.get("questions").asInt()).isEqualTo(2);
        assertThat(totals.get("answered").asInt()).isEqualTo(2);
        assertThat(totals.get("promptTokens").asInt()).isEqualTo(240);
        assertThat(totals.get("completionTokens").asInt()).isEqualTo(30);
        assertThat(totals.get("embeddingTokens").asInt()).isEqualTo(7);
        assertThat(totals.get("costUsd").asDouble()).isCloseTo(0.3035, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(objectMapper.readTree(response.body()).get("perDay")).hasSize(1);

        assertThat(get("/api/usage?days=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/usage?days=9999").statusCode()).isEqualTo(400);
    }

    @Test
    @Order(4)
    void anotherTenantSeesNoneOfThatUsage() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/usage"))
                .header("X-Tenant-Id", "tenant-without-usage-" + UUID.randomUUID().toString().substring(0, 8)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(objectMapper.readTree(response.body()).get("totals").get("questions").asInt()).isZero();
    }

    // ----- metrics -----

    @Test
    @Order(5)
    void everyStepOfTheRequestHasATimer() throws Exception {
        String metrics = get("/actuator/prometheus").body();

        for (String stage : List.of("embedding", "vector_search", "keyword_search", "rerank", "generation")) {
            assertThat(sum(metrics, "rag_stage_seconds_count", "stage=\"" + stage + "\""))
                    .as("stage " + stage).isGreaterThanOrEqualTo(1);
        }
        assertThat(sum(metrics, "rag_chat_seconds_count", "outcome=\"answered\"")).isEqualTo(2);
        assertThat(sum(metrics, "rag_stage_seconds_bucket", "stage=\"generation\"")).isPositive();   // histogram for p95
    }

    @Test
    @Order(6)
    void tokensCostAndCacheAreMetrics() throws Exception {
        String metrics = get("/actuator/prometheus").body();

        assertThat(sum(metrics, "rag_tokens_total", "kind=\"prompt\"", "tenant=\"" + TENANT + "\"")).isEqualTo(240);
        assertThat(sum(metrics, "rag_tokens_total", "kind=\"completion\"")).isEqualTo(30);
        assertThat(sum(metrics, "rag_tokens_total", "kind=\"embedding\"")).isEqualTo(7);
        assertThat(sum(metrics, "rag_cost_usd_total", "tenant=\"" + TENANT + "\"")).isCloseTo(0.3035, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(sum(metrics, "cache_gets_total", "cache=\"embedding\"", "result=\"hit\"")).isEqualTo(1);
        assertThat(sum(metrics, "cache_gets_total", "cache=\"embedding\"", "result=\"miss\"")).isEqualTo(1);
        assertThat(sum(metrics, "rag_retrieval_chunks_count")).isEqualTo(2);
    }

    @Test
    @Order(7)
    void aFailingModelShowsUpAsFailedCallsAndAnOpenCircuit() throws Exception {
        fakeOllama.chatFails();
        for (int i = 0; i < 4; i++) {
            ask("Question number " + i + " about annual leave days of employees");
        }

        String metrics = get("/actuator/prometheus").body();

        // 2 successes (tests 1 and 2), then 2 failures (50 percent of 4 calls opens the circuit), then calls refused at once
        assertThat(sum(metrics, "rag_model_call_seconds_count", "dependency=\"llm\"", "outcome=\"failure\"")).isEqualTo(2);
        assertThat(sum(metrics, "rag_model_call_seconds_count", "dependency=\"llm\"", "outcome=\"circuit_open\"")).isEqualTo(2);
        assertThat(sum(metrics, "rag_model_call_seconds_count", "dependency=\"llm\"", "outcome=\"success\"")).isGreaterThanOrEqualTo(2);
        assertThat(sum(metrics, "rag_circuit_state", "dependency=\"llm\"")).isEqualTo(2);   // 2 = OPEN
        assertThat(sum(metrics, "rag_chat_seconds_count", "outcome=\"degraded\"")).isGreaterThanOrEqualTo(4);
    }

    // ----- privacy of the telemetry -----

    @Test
    @Order(8)
    void noQuestionTextEmailOrUserIdReachesLogsMetricsOrTheUsageTable() throws Exception {
        fakeOllama.allOk();
        logs.list.clear();
        String marker = "zq9x7marker";
        String email = "secret.person@example.com";
        String userId = "user.with.identity@example.com";

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/chat"))
                .header("Content-Type", "application/json").header("X-Tenant-Id", TENANT).header("X-User-Id", userId)
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + marker + " my email is " + email
                        + ", how many days of annual leave do employees get?\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        List<String> everythingLogged = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            everythingLogged.add(event.getFormattedMessage());
            if (event.getKeyValuePairs() != null) {
                event.getKeyValuePairs().forEach(pair -> everythingLogged.add(pair.key + "=" + pair.value));
            }
            event.getMDCPropertyMap().forEach((key, value) -> everythingLogged.add(key + "=" + value));
        }
        String log = String.join("\n", everythingLogged);
        String metrics = get("/actuator/prometheus").body();

        assertThat(log).as("log output").isNotBlank().doesNotContain(marker).doesNotContain(email).doesNotContain(userId);
        assertThat(log).contains("Chat request completed");   // the summary line was written ...
        assertThat(metrics).as("prometheus output").doesNotContain(marker).doesNotContain(email).doesNotContain(userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_records WHERE tenant_id = ?", Integer.class, TENANT))
                .isPositive();
        // ... and the usage table has no text columns at all
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'usage_records'", String.class);
        assertThat(columns).doesNotContain("question", "answer", "content", "user_id", "owner_id");
    }

    // ----- helpers -----

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode ask(String question) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/chat"))
                .header("Content-Type", "application/json").header("X-Tenant-Id", TENANT).header("X-User-Id", "obs-tester")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("X-Tenant-Id", TENANT).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Adds up the values of all lines of a metric that contain all the given label texts. */
    private double sum(String prometheusText, String metricName, String... labelTexts) {
        double total = 0;
        Pattern line = Pattern.compile("^" + Pattern.quote(metricName) + "\\{([^}]*)}\\s+([0-9.eE+-]+)", Pattern.MULTILINE);
        Matcher matcher = line.matcher(prometheusText);
        while (matcher.find()) {
            boolean matchesAll = true;
            for (String label : labelTexts) {
                matchesAll &= matcher.group(1).contains(label);
            }
            if (matchesAll) {
                total += Double.parseDouble(matcher.group(2));
            }
        }
        return total;
    }
}
