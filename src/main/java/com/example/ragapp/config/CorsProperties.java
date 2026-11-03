package com.example.ragapp.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.cors" section of application.yml.
 *
 * @param allowedOrigins web sites (for example "https://hr.example.com") whose pages may call the API from a
 *                       browser. Empty = only pages served by this application itself (the chat page).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
