package com.example.ragapp.usage;

/**
 * How many tokens one step or one whole question used. (A token is a piece of a word: the unit models
 * count and bill in. Roughly 4 characters of English text.)
 *
 * @param promptTokens     sent to the language model (the question, the context, the rules)
 * @param completionTokens written by the language model (the answer)
 * @param embeddingTokens  sent to the embedding model
 */
public record TokenUsage(int promptTokens, int completionTokens, int embeddingTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0);

    public static TokenUsage ofLanguageModel(Integer promptTokens, Integer completionTokens) {
        return new TokenUsage(promptTokens == null ? 0 : promptTokens, completionTokens == null ? 0 : completionTokens, 0);
    }

    public static TokenUsage ofEmbedding(int embeddingTokens) {
        return new TokenUsage(0, 0, embeddingTokens);
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                embeddingTokens + other.embeddingTokens);
    }
}
