package com.example.ragapp.resilience;

import com.example.ragapp.observability.RagMetrics;
import org.springframework.stereotype.Component;

/**
 * The three protected dependencies of the application. Each has its own limits, because they behave
 * very differently: an embedding call takes a fraction of a second, an answer takes seconds, and
 * processing an uploaded document sends large batches.
 */
@Component
public class ProtectedCalls {

    private final ProtectedCall llm;
    private final ProtectedCall embedding;
    private final ProtectedCall ingestion;

    public ProtectedCalls(ResilienceProperties properties, RagMetrics metrics) {
        this.llm = new ProtectedCall("language model", "llm", properties.llm(), metrics);
        this.embedding = new ProtectedCall("embedding model", "embedding", properties.embedding(), metrics);
        this.ingestion = new ProtectedCall("embedding model (document processing)", "ingestion",
                properties.ingestion(), metrics);
    }

    /** Writing answers and rewriting follow-up questions. */
    public ProtectedCall llm() {
        return llm;
    }

    /** Turning a question into a vector for the search. */
    public ProtectedCall embedding() {
        return embedding;
    }

    /** Turning document chunks into vectors while a document is processed. */
    public ProtectedCall ingestion() {
        return ingestion;
    }
}
