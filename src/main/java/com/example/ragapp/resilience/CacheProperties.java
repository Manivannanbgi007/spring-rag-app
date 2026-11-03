package com.example.ragapp.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.cache" section of application.yml.
 *
 * @param embeddingMaxSize how many question embeddings are kept in memory
 * @param embeddingTtl     how long an embedding is kept
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(int embeddingMaxSize, Duration embeddingTtl) {
}
