package com.example.ragapp.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reads the "app.documents" section of application.yml. */
@ConfigurationProperties(prefix = "app.documents")
public record DocumentProperties(String uploadDir, int maxPages, int maxTitleLength) {
}
