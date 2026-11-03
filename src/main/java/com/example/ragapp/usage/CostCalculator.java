package com.example.ragapp.usage;

import org.springframework.stereotype.Component;

/** Turns token counts into an estimated price in US dollars. */
@Component
public class CostCalculator {

    private final CostProperties prices;

    public CostCalculator(CostProperties prices) {
        this.prices = prices;
    }

    public double estimate(TokenUsage usage) {
        return usage.promptTokens() / 1000.0 * prices.llmInputPer1k()
                + usage.completionTokens() / 1000.0 * prices.llmOutputPer1k()
                + usage.embeddingTokens() / 1000.0 * prices.embeddingPer1k();
    }
}
