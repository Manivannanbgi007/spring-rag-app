package com.example.ragapp.ingestion;

import java.util.List;

import com.example.ragapp.document.DocumentStatus;
import com.example.ragapp.document.PolicyDocument;
import com.example.ragapp.document.PolicyDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once at startup. If the application was stopped while documents were being processed,
 * those documents would stay "in progress" forever. We mark them FAILED so the user
 * can simply upload the file again.
 */
@Component
public class StaleDocumentRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleDocumentRecovery.class);

    private final PolicyDocumentRepository repository;
    private final ChunkStore chunkStore;

    public StaleDocumentRecovery(PolicyDocumentRepository repository, ChunkStore chunkStore) {
        this.repository = repository;
        this.chunkStore = chunkStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<PolicyDocument> stale = repository.findByStatusIn(
                List.of(DocumentStatus.QUEUED, DocumentStatus.PARSING, DocumentStatus.EMBEDDING));

        for (PolicyDocument document : stale) {
            chunkStore.deleteByDocumentId(document.getId());
            document.setStatus(DocumentStatus.FAILED);
            document.setStatusMessage("Processing was interrupted by an application restart. "
                    + "Please upload the file again.");
            document.setChunkCount(null);
            repository.save(document);
            log.warn("Document {} was interrupted by a restart and is now FAILED", document.getId());
        }
    }
}
