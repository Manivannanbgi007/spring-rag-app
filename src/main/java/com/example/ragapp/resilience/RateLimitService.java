package com.example.ragapp.resilience;

import java.time.Duration;

import com.example.ragapp.common.Caller;
import com.example.ragapp.common.RateLimitExceededException;
import com.example.ragapp.observability.RagMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * How often one user (or one tenant) may use the application.
 *
 * Why: one user with a script, or one broken browser tab that keeps retrying, must not use up the
 * language model for everybody. This is different from the limits in ProtectedCall: those protect
 * the model from too much work, this protects the other users from one heavy user.
 *
 * Every user and every tenant gets a small counter ("rate limiter") that allows N requests per period.
 * The counters live in a cache that forgets users who have been quiet for a while, so memory stays small.
 * Counters are kept in memory of this one server. With several servers, use a shared store (for example Redis).
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimitProperties properties;
    private final RagMetrics metrics;
    private final Cache<String, RateLimiter> userLimiters = newCache();
    private final Cache<String, RateLimiter> tenantLimiters = newCache();
    private final Cache<String, RateLimiter> uploadLimiters = newCache();

    public RateLimitService(RateLimitProperties properties, RagMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Call before every question. Throws RateLimitExceededException (HTTP 429) when the limit is reached. */
    public void checkQuestion(Caller caller) {
        take("user", userLimiters, caller.tenantId() + "/" + caller.userId(), properties.userPerMinute(),
                Duration.ofMinutes(1), "You are asking questions too quickly. Please wait a moment.");
        take("tenant", tenantLimiters, caller.tenantId(), properties.tenantPerMinute(), Duration.ofMinutes(1),
                "Too many questions are being asked in your organisation right now. Please wait a moment.");
    }

    /** Call before every upload. */
    public void checkUpload(Caller caller) {
        take("upload", uploadLimiters, caller.tenantId(), properties.uploadsPerHourPerTenant(), Duration.ofHours(1),
                "Too many documents were uploaded in the last hour. Please try again later.");
    }

    private void take(String scope, Cache<String, RateLimiter> limiters, String key, int limit, Duration period,
                      String message) {
        if (limit <= 0) {
            return;   // switched off
        }
        RateLimiter limiter = limiters.get(key, k -> RateLimiter.of(k, RateLimiterConfig.custom()
                .limitForPeriod(limit)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)   // no waiting: over the limit = rejected
                .build()));

        if (!limiter.acquirePermission()) {
            log.warn("Rate limit reached ({} per {})", limit, period);
            metrics.rateLimited(scope);
            throw new RateLimitExceededException(message, Math.max(1, period.toSeconds()));
        }
    }

    private static Cache<String, RateLimiter> newCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofHours(2))
                .build();
    }
}
