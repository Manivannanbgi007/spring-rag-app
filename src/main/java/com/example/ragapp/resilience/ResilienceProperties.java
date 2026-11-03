package com.example.ragapp.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.resilience" section of application.yml: one rule set for each outgoing dependency.
 *
 * @param llm       the language model that writes answers (slow, one call = a few seconds)
 * @param embedding the embedding model used to search (fast, one call = a fraction of a second)
 * @param ingestion the embedding model used while processing uploaded documents (batches, slower, in the background)
 * @param searchTimeout longest time a search query may run in the database
 */
@ConfigurationProperties(prefix = "app.resilience")
public record ResilienceProperties(PolicyProperties llm, PolicyProperties embedding, PolicyProperties ingestion,
                                   Duration searchTimeout) {
}
