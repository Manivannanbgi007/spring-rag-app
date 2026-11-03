package com.example.ragapp.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.privacy" section of application.yml.
 *
 * @param maskPii true = personal data and secrets are replaced by placeholders before any text
 *                is sent to a model (the language model and the embedding model)
 */
@ConfigurationProperties(prefix = "app.privacy")
public record PrivacyProperties(boolean maskPii) {
}
