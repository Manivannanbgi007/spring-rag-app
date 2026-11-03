package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.ragapp.chat.ChatRequest;
import com.example.ragapp.chat.ChatResponse;
import com.example.ragapp.chat.ChatService;
import com.example.ragapp.common.Caller;
import com.example.ragapp.document.DocumentResponse;
import com.example.ragapp.document.DocumentService;
import com.example.ragapp.document.DocumentStatus;
import com.example.ragapp.retrieval.RetrievalService;
import com.example.ragapp.retrieval.RetrievedChunk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * THE SAMPLE DOCUMENTS END TO END: uploads all nine files of sample-docs/ (including the 500-page handbook)
 * and asks the questions of sample-questions.json. It is slow, because the 500 pages must be embedded by Ollama
 * (several minutes on a CPU), so it is not part of the normal "mvnw test". Run it with:
 *
 *     mvnw test -Pevaluation -Dtest=SampleDocsEvaluationTest
 *
 * Needs the Docker stack.
 */
@Tag("evaluation")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SampleDocsEvaluationTest {

    private static final String TENANT = "eval-samples-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Path DIRECTORY = Path.of("sample-docs");
    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    @Autowired private DocumentService documentService;
    @Autowired private RetrievalService retrievalService;
    @Autowired private ChatService chatService;
    @Autowired private JdbcTemplate jdbc;

    private final List<SampleQuestion> questions = SampleQuestion.load();

    @BeforeAll
    void uploadAllSampleDocuments() throws Exception {
        try (var files = Files.list(DIRECTORY)) {
            for (Path file : files.sorted().toList()) {
                long start = System.currentTimeMillis();
                upload(file);
                System.out.printf("SAMPLE %s processed in %.1f seconds%n", file.getFileName(), (System.currentTimeMillis() - start) / 1000.0);
            }
        }
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM usage_records WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'tenant_id' = ?", TENANT);
        jdbc.update("DELETE FROM documents WHERE tenant_id = ?", TENANT);
    }

    @Test
    void theSearchFindsThePassageWithTheAnswer() {
        List<String> misses = new ArrayList<>();
        int total = 0;
        for (SampleQuestion question : questions) {
            if (!question.answerable()) {
                continue;
            }
            total++;
            List<RetrievedChunk> chunks = retrievalService.search(TENANT, question.question()).chunks();
            boolean found = chunks.stream().anyMatch(chunk -> question.accepts(chunk.fileName())
                    && Pattern.compile(question.text(), REGEX_FLAGS).matcher(chunk.text()).find());
            if (!found) {
                misses.add(question.id() + " " + question.question());
            }
        }
        double hitRate = total == 0 ? 0 : (total - misses.size()) / (double) total;
        System.out.printf("SAMPLE SEARCH: the right passage was found for %d of %d questions%s%n",
                total - misses.size(), total, misses.isEmpty() ? "" : ", missing: " + misses);
        assertThat(hitRate).as("share of questions for which the search found the answer; missing: " + misses)
                .isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void theAnswersAreCorrectCitedAndRefusedWhenUnknown() {
        List<String> problems = new ArrayList<>();
        for (SampleQuestion question : questions) {
            ChatResponse response = chatService.ask(new Caller(TENANT, "eval-" + question.id().toLowerCase()),
                    new ChatRequest(question.question(), null));
            List<String> own = new ArrayList<>();

            if (!question.answerable()) {
                if (response.found()) {
                    own.add("should say that it cannot find the answer, but answered: " + response.answer());
                }
            } else {
                if (!response.found()) {
                    own.add("refused to answer");
                }
                for (String fact : question.facts()) {
                    if (!Pattern.compile(fact, REGEX_FLAGS).matcher(response.answer()).find()) {
                        own.add("answer lacks /" + fact + "/: " + response.answer().replaceAll("\\s+", " "));
                    }
                }
                if (response.found() && response.sources().stream().noneMatch(source -> question.accepts(source.fileName()))) {
                    own.add("no source from the right document: " + response.sources().stream().map(s -> s.fileName()).toList());
                }
            }
            System.out.println("SAMPLE " + question.id() + (own.isEmpty() ? " ok" : " PROBLEM " + own));
            own.forEach(problem -> problems.add(question.id() + ": " + problem));
        }
        // small models are not perfect: one wrong answer in ten is tolerated, as in the quality gates of the main evaluation
        assertThat(problems.stream().map(p -> p.substring(0, 3)).distinct().count())
                .as("questions with a problem: " + problems)
                .isLessThanOrEqualTo(Math.max(2, questions.size() / 10));
    }

    private void upload(Path file) throws Exception {
        String name = file.getFileName().toString();
        String contentType = name.endsWith(".pdf") ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        DocumentResponse uploaded = documentService.upload(TENANT,
                new MockMultipartFile("file", name, contentType, Files.readAllBytes(file)), name, "2026-01");

        for (int attempt = 0; attempt < 12_000; attempt++) {   // up to 40 minutes
            DocumentResponse state = documentService.get(TENANT, uploaded.id());
            if (state.status() == DocumentStatus.COMPLETED) {
                return;
            }
            if (state.status() == DocumentStatus.FAILED) {
                throw new IllegalStateException(name + " could not be processed: " + state.statusMessage());
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(name + " was not processed in time");
    }
}
