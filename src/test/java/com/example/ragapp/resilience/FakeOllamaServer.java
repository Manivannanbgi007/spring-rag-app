package com.example.ragapp.resilience;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A pretend Ollama for tests. It answers the two calls the application makes:
 *   POST /api/embed  (embedding)   and   POST /api/chat  (language model, normal and streamed)
 * and it can be told to behave badly: return errors, or answer slowly.
 * Tests use it to see how the whole application reacts to a failing model, without touching the real one.
 */
public class FakeOllamaServer implements AutoCloseable {

    enum Mode {
        OK,           // answers normally
        ERROR,        // answers HTTP 500
        SLOW          // waits a few seconds before answering (longer than the application's timeout)
    }

    /** The text the pretend language model "writes". It matches the test document, like a good answer would. */
    static final String ANSWER = "Employees are entitled to 24 days of paid annual leave per calendar year.";

    /** The token counts the pretend model reports (the real Ollama reports them in the same fields). */
    static final int EMBEDDING_TOKENS = 7;
    static final int PROMPT_TOKENS = 120;
    static final int COMPLETION_TOKENS = 15;

    private final HttpServer server;
    private final String vectorJson;

    volatile Mode embeddingMode = Mode.OK;
    volatile Mode chatMode = Mode.OK;
    /** The next N embedding requests fail with HTTP 500, then the model works again (a short hiccup). */
    final AtomicInteger embeddingFailuresLeft = new AtomicInteger();
    final AtomicInteger embeddingRequests = new AtomicInteger();
    final AtomicInteger chatRequests = new AtomicInteger();

    public FakeOllamaServer() throws IOException {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < 768; i++) {
            vector.append(i == 0 ? "" : ",").append("0.01");
        }
        this.vectorJson = vector.append("]").toString();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/embed", exchange -> handle(exchange, embeddingRequests, () -> embeddingMode, false));
        server.createContext("/api/chat", exchange -> handle(exchange, chatRequests, () -> chatMode, true));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        embeddingMode = Mode.OK;
        chatMode = Mode.OK;
        embeddingFailuresLeft.set(0);
        embeddingRequests.set(0);
        chatRequests.set(0);
    }

    private void handle(HttpExchange exchange, AtomicInteger counter, java.util.function.Supplier<Mode> mode,
                        boolean isChat) throws IOException {
        counter.incrementAndGet();
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        boolean hiccup = !isChat && embeddingFailuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
        if (hiccup) {
            send(exchange, 500, "application/json", "{\"error\":\"short hiccup\"}");
            return;
        }

        switch (mode.get()) {
            case ERROR -> send(exchange, 500, "application/json", "{\"error\":\"the model crashed\"}");
            case SLOW -> {
                sleep(4000);
                reply(exchange, requestBody, isChat);
            }
            default -> reply(exchange, requestBody, isChat);
        }
    }

    private void reply(HttpExchange exchange, String requestBody, boolean isChat) throws IOException {
        if (!isChat) {
            send(exchange, 200, "application/json",
                    "{\"model\":\"fake\",\"embeddings\":[" + vectorJson + "],\"prompt_eval_count\":" + EMBEDDING_TOKENS + "}");
            return;
        }
        boolean streamed = requestBody.replace(" ", "").contains("\"stream\":true");
        if (!streamed) {
            send(exchange, 200, "application/json", chatLine(ANSWER, true));
            return;
        }
        // streamed: one JSON object per line, one word at a time
        StringBuilder lines = new StringBuilder();
        for (String word : ANSWER.split("(?<= )")) {
            lines.append(chatLine(word, false)).append('\n');
        }
        lines.append(chatLine("", true)).append('\n');
        send(exchange, 200, "application/x-ndjson", lines.toString());
    }

    private String chatLine(String content, boolean done) {
        return "{\"model\":\"fake\",\"created_at\":\"2025-01-01T00:00:00Z\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + content + "\"},\"done\":" + done
                + (done ? ",\"done_reason\":\"stop\",\"prompt_eval_count\":" + PROMPT_TOKENS
                + ",\"eval_count\":" + COMPLETION_TOKENS : "") + "}";
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // the application gave up waiting (timeout) and closed the connection: expected in some tests
        } finally {
            exchange.close();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** From now on the language model answers with HTTP 500. */
    public void chatFails() {
        chatMode = Mode.ERROR;
    }

    /** Both models work again. */
    public void allOk() {
        embeddingMode = Mode.OK;
        chatMode = Mode.OK;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
