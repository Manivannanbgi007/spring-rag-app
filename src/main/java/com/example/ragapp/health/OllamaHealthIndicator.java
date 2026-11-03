package com.example.ragapp.health;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Shows up as "ollama" in GET /actuator/health.
 * Reports DOWN if Ollama cannot be reached or if a required model is not downloaded.
 */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final List<String> requiredModels;

    public OllamaHealthIndicator(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model}") String chatModel,
            @Value("${spring.ai.ollama.embedding.options.model}") String embeddingModel) {

        // Short timeouts so a slow Ollama never makes the health check hang
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.requiredModels = List.of(chatModel, embeddingModel);
    }

    @Override
    public Health health() {
        try {
            List<String> installedModels = fetchInstalledModelNames();
            List<String> missingModels = new ArrayList<>();

            for (String required : requiredModels) {
                if (!isInstalled(required, installedModels)) {
                    missingModels.add(required);
                }
            }

            if (missingModels.isEmpty()) {
                return Health.up().withDetail("models", requiredModels).build();
            }
            return Health.down().withDetail("missingModels", missingModels).build();

        } catch (Exception e) {
            return Health.down().withDetail("error", "Cannot reach Ollama: " + e.getMessage()).build();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchInstalledModelNames() {
        Map<String, Object> response = restClient.get().uri("/api/tags").retrieve().body(Map.class);
        List<Map<String, Object>> models = (List<Map<String, Object>>) response.get("models");
        return models.stream().map(model -> (String) model.get("name")).toList();
    }

    // Ollama lists "nomic-embed-text" as "nomic-embed-text:latest", so accept both spellings
    private boolean isInstalled(String requiredModel, List<String> installedModels) {
        return installedModels.contains(requiredModel)
                || installedModels.contains(requiredModel + ":latest");
    }
}
