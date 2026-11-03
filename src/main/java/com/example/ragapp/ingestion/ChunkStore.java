package com.example.ragapp.ingestion;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.resilience.ProtectedCalls;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring AI's VectorStore (our pgvector table).
 * The rest of the app talks to this class, not to the vector database directly.
 */
@Component
public class ChunkStore {

    private final VectorStore vectorStore;
    private final ProtectedCalls protectedCalls;

    public ChunkStore(VectorStore vectorStore, ProtectedCalls protectedCalls) {
        this.vectorStore = vectorStore;
        this.protectedCalls = protectedCalls;
    }

    /**
     * Creates the embedding (vector) for each chunk and saves chunk + vector.
     *
     * It is safe to repeat this call after a failure: every chunk has a fixed id, so saving a chunk a
     * second time replaces it and never creates a duplicate. That is why a retry is allowed here.
     * (A call that creates new rows each time must never be retried automatically.)
     */
    public void save(List<Document> chunks) {
        protectedCalls.ingestion().call(() -> {
            vectorStore.add(chunks);
            return null;
        });
    }

    /** Removes every chunk that belongs to one document. */
    public void deleteByDocumentId(UUID documentId) {
        vectorStore.delete(new FilterExpressionBuilder().eq("document_id", documentId.toString()).build());
    }
}
