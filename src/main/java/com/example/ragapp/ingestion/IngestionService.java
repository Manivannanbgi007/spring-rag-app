package com.example.ragapp.ingestion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.ragapp.common.AiServiceUnavailableException;
import com.example.ragapp.common.ServiceBusyException;
import com.example.ragapp.document.DocumentStatus;
import com.example.ragapp.document.DocumentStorage;
import com.example.ragapp.document.DocumentType;
import com.example.ragapp.document.PolicyDocument;
import com.example.ragapp.document.PolicyDocumentRepository;
import com.example.ragapp.ingestion.parser.DocumentParser;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.ingestion.parser.ParsedDocument;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * The background pipeline that makes a document searchable:
 *
 *   read file  ->  split into chunks  ->  add metadata  ->  embed + store in batches
 *
 * The status of the document is updated after every step, so the user can follow progress.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final PolicyDocumentRepository repository;
    private final DocumentStorage storage;
    private final ChunkStore chunkStore;
    private final IngestionProperties properties;
    private final RagMetrics metrics;
    private final Map<DocumentType, DocumentParser> parsers = new HashMap<>();
    private final TokenTextSplitter splitter;

    public IngestionService(PolicyDocumentRepository repository, DocumentStorage storage,
                            ChunkStore chunkStore, IngestionProperties properties, RagMetrics metrics,
                            List<DocumentParser> parserList) {
        this.metrics = metrics;
        this.repository = repository;
        this.storage = storage;
        this.chunkStore = chunkStore;
        this.properties = properties;
        parserList.forEach(parser -> parsers.put(parser.supportedType(), parser));

        // Splits text into pieces of about "chunkSize" tokens, preferring to cut at sentence ends
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.chunkSize())
                .withMinChunkSizeChars(properties.chunkSize() * 3 / 4)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(100_000)
                .withKeepSeparator(true)
                .build();
    }

    /** Runs in a background thread (see AsyncConfig), so the upload request returns immediately. */
    @Async("ingestionExecutor")
    public void ingestAsync(UUID documentId) {
        MDC.put("documentId", documentId.toString().substring(0, 8));   // shows on every log line
        try {
            repository.findById(documentId).ifPresentOrElse(
                    this::ingest,
                    () -> log.warn("Document {} no longer exists, nothing to process", documentId));
        } finally {
            MDC.remove("documentId");
        }
    }

    /** Times and traces the whole processing of one document (metric: rag_ingestion_seconds{outcome}). */
    private void ingest(PolicyDocument document) {
        RagMetrics.IngestionObservation observation = metrics.startIngestion();
        boolean completed = false;
        try (Observation.Scope scope = observation.open()) {
            completed = process(document);
        } finally {
            observation.finish(completed);
        }
    }

    /** @return true when the document ended COMPLETED, false when it ended FAILED */
    private boolean process(PolicyDocument document) {
        long startTime = System.currentTimeMillis();
        log.info("Ingestion started for '{}'", document.getFileName());

        try {
            // Step 1: read the file
            document = updateStatus(document, DocumentStatus.PARSING, "Reading the document");
            chunkStore.deleteByDocumentId(document.getId());   // makes re-processing safe: no duplicate chunks

            DocumentType type = DocumentType.fromFileName(document.getFileName()).orElseThrow();
            Path file = storage.resolve(document.getStoragePath());
            ParsedDocument parsed = parsers.get(type).parse(file);
            if (parsed.sections().isEmpty()) {
                throw new IngestionException("No readable text was found. Scanned documents (images) "
                        + "are not supported yet.");
            }
            document.setPageCount(parsed.pageCount());

            // Step 2: split into chunks and add metadata
            List<Document> chunks = createChunks(document, parsed.sections());
            if (chunks.isEmpty()) {
                throw new IngestionException("No readable text was found in the document.");
            }
            document.setChunkCount(chunks.size());

            // Step 3: embed and store, a few chunks at a time
            document = updateStatus(document, DocumentStatus.EMBEDDING, "Created " + chunks.size() + " chunks");
            document = embedInBatches(document, chunks);

            document = updateStatus(document, DocumentStatus.COMPLETED, "Ready for questions");
            log.info("Ingestion completed: {} pages, {} chunks, {} ms",
                    document.getPageCount(), chunks.size(), System.currentTimeMillis() - startTime);
            return true;

        } catch (IngestionException e) {
            // A problem with the document itself: tell the user exactly what is wrong
            log.warn("Ingestion failed: {}", e.getMessage());
            failDocument(document, e.getMessage());
            return false;
        } catch (AiServiceUnavailableException | ServiceBusyException e) {
            // The embedding model did not answer, even after the retries
            log.warn("Ingestion failed: {}", e.getMessage());
            failDocument(document, "The embedding model is not available right now. "
                    + "Please upload the file again in a few minutes.");
            return false;
        } catch (Exception e) {
            // A technical problem (database down ...): log details, show a short message
            log.error("Ingestion failed unexpectedly", e);
            failDocument(document, "Processing failed. Please try again later. If it keeps failing, contact support.");
            return false;
        }
    }

    private List<Document> createChunks(PolicyDocument document, List<Document> sections) {
        List<Document> pieces = splitter.apply(sections);   // each piece keeps its section's metadata
        List<Document> chunks = new ArrayList<>();

        for (int index = 0; index < pieces.size(); index++) {
            Document piece = pieces.get(index);

            Map<String, Object> metadata = new HashMap<>(piece.getMetadata());
            metadata.put("document_id", document.getId().toString());
            metadata.put("tenant_id", document.getTenantId());
            metadata.put("file_name", document.getFileName());
            metadata.put("title", document.getTitle());
            metadata.put("version", document.getVersion());
            metadata.put("chunk_index", index);
            metadata.put("uploaded_at", document.getCreatedAt().toString());

            // Same document + same position = same id. Saving twice replaces instead of duplicating.
            String chunkId = UUID.nameUUIDFromBytes(
                    (document.getId() + ":" + index).getBytes(StandardCharsets.UTF_8)).toString();

            chunks.add(Document.builder().id(chunkId).text(piece.getText()).metadata(metadata).build());
        }
        return chunks;
    }

    private PolicyDocument embedInBatches(PolicyDocument document, List<Document> chunks) {
        int batchSize = properties.embeddingBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            chunkStore.save(chunks.subList(start, end));

            document = updateStatus(document, DocumentStatus.EMBEDDING,
                    "Embedded " + end + " of " + chunks.size() + " chunks");
        }
        return document;
    }

    private PolicyDocument updateStatus(PolicyDocument document, DocumentStatus status, String message) {
        document.setStatus(status);
        document.setStatusMessage(message);
        return repository.save(document);
    }

    private void failDocument(PolicyDocument document, String message) {
        try {
            chunkStore.deleteByDocumentId(document.getId());   // do not leave half-processed chunks behind
        } catch (Exception cleanupError) {
            log.warn("Could not remove partial chunks", cleanupError);
        }
        document.setChunkCount(null);
        updateStatus(document, DocumentStatus.FAILED, message);
    }
}
