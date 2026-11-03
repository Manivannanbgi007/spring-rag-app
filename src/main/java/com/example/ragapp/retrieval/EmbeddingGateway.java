package com.example.ragapp.retrieval;

import java.util.List;

import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.resilience.CacheProperties;
import com.example.ragapp.resilience.ProtectedCalls;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * The only way the search turns a question into a vector. Three things happen here:
 *
 *   1. CACHE   - the same question gives the same vector every time, so a vector that was
 *                calculated before is reused (also for repeated questions of different users).
 *                It saves a model call, which is the slowest part of the search, and the cost of its tokens.
 *                The cache holds the question text only in memory, never on disk, and the
 *                question has been masked for personal data before it gets here.
 *   2. PROTECT - a call that is really needed goes through timeout, retry and circuit breaker
 *                (see ProtectedCall).
 *   3. MEASURE - the call is timed and traced (stage "embedding"), its tokens are counted, and the cache
 *                hit rate is a metric (cache_gets_total{cache="embedding"}).
 *
 * Why cache only embeddings, and not answers or document lookups? An answer depends on the
 * documents, which can change at any moment, and users may rate a repeated wrong answer again and
 * again. A document lookup is one small indexed query. The embedding of a text, however, never
 * changes, and it is expensive to calculate.
 */
@Component
public class EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGateway.class);

    private final EmbeddingModel embeddingModel;
    private final ProtectedCalls protectedCalls;
    private final RagMetrics metrics;
    private final Cache<String, float[]> cache;

    /**
     * @param vector the question as a vector
     * @param tokens tokens the embedding model used; 0 when the vector came from the cache (it was free)
     */
    public record QueryEmbedding(float[] vector, int tokens) {
    }

    public EmbeddingGateway(EmbeddingModel embeddingModel, ProtectedCalls protectedCalls, RagMetrics metrics,
                            CacheProperties cacheProperties) {
        this.embeddingModel = embeddingModel;
        this.protectedCalls = protectedCalls;
        this.metrics = metrics;
        this.cache = Caffeine.newBuilder()
                .maximumSize(cacheProperties.embeddingMaxSize())
                .expireAfterWrite(cacheProperties.embeddingTtl())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(metrics.meters(), cache, "embedding");   // hit and miss counters
    }

    /**
     * @throws com.example.ragapp.common.AiServiceUnavailableException if the embedding model cannot be reached
     * @throws com.example.ragapp.common.ServiceBusyException          if too many embedding calls run at once
     */
    public QueryEmbedding embed(String text) {
        String key = text.strip();
        float[] cached = cache.getIfPresent(key);
        if (cached != null) {
            log.debug("Embedding found in the cache");
            return new QueryEmbedding(cached, 0);
        }

        EmbeddingResponse response = metrics.stage("embedding", () -> protectedCalls.embedding().call(() ->
                embeddingModel.call(new EmbeddingRequest(List.of(key), null))));

        float[] vector = response.getResult().getOutput();
        cache.put(key, vector);
        return new QueryEmbedding(vector, tokensOf(response));
    }

    private int tokensOf(EmbeddingResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Integer tokens = usage == null ? null : usage.getPromptTokens();
        return tokens == null ? 0 : tokens;
    }

    /** Hits and misses, for tests. */
    public CacheStats cacheStats() {
        return cache.stats();
    }
}
