package com.example.ragapp.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reads the "app.ingestion" section of application.yml. */
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(int chunkSize, int embeddingBatchSize, int threads, int queueSize) {
}
