package com.example.ragapp.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.rag" section of application.yml.
 *
 * @param topK                number of chunks finally given to the language model
 * @param similarityThreshold chunks found by the vector search that are less similar than this (0 to 1) are ignored
 * @param maxContextChars     limit for the text sent to the language model
 * @param snippetLength       length of the text preview returned with each source
 * @param hybridEnabled       true = vector search + keyword search, false = vector search only
 * @param rerankEnabled       true = look closer at the best candidates and reorder them
 * @param candidates          how many results each of the two searches returns
 * @param rerankCandidates    how many of the best combined results are reranked
 * @param rrfK                softening constant of the rank fusion (60 is the usual value)
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        int topK,
        double similarityThreshold,
        int maxContextChars,
        int snippetLength,
        boolean hybridEnabled,
        boolean rerankEnabled,
        int candidates,
        int rerankCandidates,
        int rrfK) {
}
