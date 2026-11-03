package com.example.ragapp.usage;

/**
 * What one question used, returned with the answer.
 *
 * @param estimatedCostUsd the estimated price in US dollars; 0 while the models run locally
 */
public record UsageInfo(int promptTokens, int completionTokens, int embeddingTokens, double estimatedCostUsd) {

    public static UsageInfo of(TokenUsage usage, double cost) {
        return new UsageInfo(usage.promptTokens(), usage.completionTokens(), usage.embeddingTokens(), cost);
    }
}
