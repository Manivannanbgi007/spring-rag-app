package com.example.ragapp.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.cost" section of application.yml: what one thousand tokens cost, in US dollars.
 *
 * The models of this project run on your own computer, so the real price is 0 and the defaults are 0.
 * If you switch to a cloud provider, enter its prices and the cost per question, per day and per tenant
 * appears in the usage report and in the metrics. Example for OpenAI gpt-4o-mini and
 * text-embedding-3-small (check the current prices of your provider): 0.00015 / 0.0006 / 0.00002.
 */
@ConfigurationProperties(prefix = "app.cost")
public record CostProperties(double llmInputPer1k, double llmOutputPer1k, double embeddingPer1k) {
}
