package com.example.ragapp.evaluation;

import static com.example.ragapp.TestPdfs.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.ragapp.document.DocumentResponse;
import com.example.ragapp.document.DocumentService;
import com.example.ragapp.document.DocumentStatus;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * The test documents: a small employee handbook, written in src/test/resources/evaluation/corpus.json.
 * One entry of "pages" becomes one page of a PDF. The tests upload it through the real DocumentService, so
 * parsing, chunking, embedding and storing are part of what is evaluated.
 */
public record EvalCorpus(String fileName, String title, String version, List<Page> pages) {

    public record Page(List<String> lines) {
        public String text() {
            return String.join("\n", lines);
        }
    }

    public static EvalCorpus load() {
        try (InputStream in = EvalCorpus.class.getResourceAsStream("/evaluation/corpus.json")) {
            return JsonMapper.builder().build().readValue(in, EvalCorpus.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Text of a page (1 = the first page). Used as the evidence when checking an answer. */
    public String pageText(int page) {
        return pages.get(page - 1).text();
    }

    public byte[] toPdf() {
        try {
            @SuppressWarnings("unchecked")
            List<String>[] pageLines = pages.stream().map(Page::lines).toArray(List[]::new);
            return pdf(pageLines);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Uploads the corpus for a tenant and waits until it is processed. A line with a random id is added
     * to the last page, so that the same corpus can be uploaded again for another tenant or chunk size.
     *
     * @return the id of the document
     */
    public UUID ingest(DocumentService documentService, String tenantId) throws InterruptedException {
        DocumentResponse uploaded = documentService.upload(tenantId,
                new MockMultipartFile("file", fileName, "application/pdf", toPdf()), title, version);

        for (int attempt = 0; attempt < 600; attempt++) {
            DocumentResponse state = documentService.get(tenantId, uploaded.id());
            if (state.status() == DocumentStatus.COMPLETED) {
                return uploaded.id();
            }
            if (state.status() == DocumentStatus.FAILED) {
                throw new IllegalStateException("The corpus could not be processed: " + state.statusMessage());
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("The corpus was not processed in time");
    }

    /** For reports: how many pages and facts. */
    public Map<String, Object> summary() {
        return Map.of("file", fileName, "pages", pages.size());
    }
}
