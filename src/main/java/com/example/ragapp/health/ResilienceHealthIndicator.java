package com.example.ragapp.health;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.ragapp.resilience.ProtectedCall;
import com.example.ragapp.resilience.ProtectedCalls;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Shows up as "resilience" in GET /actuator/health: the state of each circuit breaker
 * (CLOSED = normal, OPEN = the model failed repeatedly and calls are refused for a while,
 * HALF_OPEN = testing whether it works again) and how many call slots are free.
 *
 * The status stays UP on purpose: an open circuit means "degraded", not "this application is broken",
 * and the application still answers (with passages or keyword search).
 */
@Component
public class ResilienceHealthIndicator implements HealthIndicator {

    private final ProtectedCalls protectedCalls;

    public ResilienceHealthIndicator(ProtectedCalls protectedCalls) {
        this.protectedCalls = protectedCalls;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        describe(health, "llm", protectedCalls.llm());
        describe(health, "embedding", protectedCalls.embedding());
        describe(health, "ingestion", protectedCalls.ingestion());
        return health.build();
    }

    private void describe(Health.Builder health, String key, ProtectedCall call) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("circuit", call.circuitState());
        details.put("freeSlots", call.freeSlots());
        health.withDetail(key, details);
    }
}
