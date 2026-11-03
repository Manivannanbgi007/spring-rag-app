package com.example.ragapp.resilience;

import java.time.Duration;

/**
 * The protection rules for ONE kind of outgoing call (language model, embedding model ...).
 * The values come from "app.resilience.*" in application.yml.
 *
 * @param timeout               longest wait for one try. For a streamed answer: longest wait for the FIRST word
 * @param streamIdleTimeout     streamed answers only: longest pause between two words
 * @param maxConcurrentCalls    how many calls may run at the same time (the rest wait, see maxWaitForSlot)
 * @param maxWaitForSlot        how long a call waits for a free slot before it is rejected as "busy"
 * @param retryAttempts         number of tries in total (1 = no retry)
 * @param retryBackoff          wait before the second try; doubles for every further try (with a little random jitter)
 * @param circuitBreakerEnabled whether failing calls should switch the circuit breaker open
 * @param failureRatePercent    circuit opens when at least this share of the recent calls failed
 * @param minimumCalls          the failure rate is only judged after this many calls
 * @param windowSize            how many recent calls are looked at
 * @param openDuration          how long the circuit stays open (all calls fail immediately) before it tests again
 * @param callsPerMinute        limit for all users together (to protect a paid provider); 0 = no limit
 */
public record PolicyProperties(
        Duration timeout,
        Duration streamIdleTimeout,
        int maxConcurrentCalls,
        Duration maxWaitForSlot,
        int retryAttempts,
        Duration retryBackoff,
        boolean circuitBreakerEnabled,
        int failureRatePercent,
        int minimumCalls,
        int windowSize,
        Duration openDuration,
        int callsPerMinute) {
}
