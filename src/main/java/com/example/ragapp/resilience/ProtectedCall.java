package com.example.ragapp.resilience;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.example.ragapp.common.AiServiceUnavailableException;
import com.example.ragapp.common.ServiceBusyException;
import com.example.ragapp.observability.RagMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Protects ONE outgoing dependency (for example the language model) against slowness and failure.
 * Every call goes through these protections, in this order:
 *
 *   1. provider rate limit : too many calls per minute overall?  -> "busy"
 *   2. concurrency limit   : too many calls at the same time?    -> wait a little, then "busy"
 *   3. retry with backoff  : failed in a way that may pass?      -> try again after 1s, 2s, 4s ...
 *   4. circuit breaker     : the last calls mostly failed?       -> fail immediately for a while
 *   5. timeout             : no answer in time?                  -> give up on this try
 *
 * Why each one matters:
 *   - timeout          a hanging model must not hang our users
 *   - retry            a short hiccup (restart, overload) often passes by itself
 *   - circuit breaker  when the model is really down, retrying makes things worse and slow:
 *                      fail fast, and let the model recover
 *   - concurrency      a CPU model handles a few questions at a time; more only makes all of them slower
 *   - rate limit       protects a paid provider (and its bill)
 *
 * Only "transient" failures are retried and counted by the circuit breaker (see isTransient):
 * a refused connection, a timeout, a server error. A wrong request (HTTP 400, 404) would fail again,
 * so retrying it is pointless.
 *
 * When everything fails, the caller gets an AiServiceUnavailableException or a ServiceBusyException,
 * which it can turn into a fallback (see RagChatService) or an HTTP 503.
 */
public class ProtectedCall {

    private static final Logger log = LoggerFactory.getLogger(ProtectedCall.class);

    private final String name;          // for example "language model"; used in log lines and messages
    private final String dependency;    // short key for metrics: "llm", "embedding", "ingestion"
    private final RagMetrics metrics;
    private final PolicyProperties config;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;    // null when switched off
    private final RateLimiter providerRateLimiter;  // null when there is no limit
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Without real metrics (used by simple tests). */
    public ProtectedCall(String name, PolicyProperties config) {
        this(name, "test", config, RagMetrics.forTests());
    }

    public ProtectedCall(String name, String dependency, PolicyProperties config, RagMetrics metrics) {
        this.name = name;
        this.dependency = dependency;
        this.metrics = metrics;
        this.config = config;

        this.bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(config.maxConcurrentCalls())
                .maxWaitDuration(config.maxWaitForSlot())
                .build());

        this.retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(config.retryAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(config.retryBackoff(), 2.0, 0.3))
                .retryOnException(ProtectedCall::isTransient)
                .build());

        this.circuitBreaker = config.circuitBreakerEnabled()
                ? CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                        .failureRateThreshold(config.failureRatePercent())
                        .minimumNumberOfCalls(config.minimumCalls())
                        .slidingWindowSize(config.windowSize())
                        .waitDurationInOpenState(config.openDuration())
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .recordException(ProtectedCall::isTransient)
                        .build())
                : null;

        this.providerRateLimiter = config.callsPerMinute() > 0
                ? RateLimiter.of(name, RateLimiterConfig.custom()
                        .limitForPeriod(config.callsPerMinute())
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)   // no waiting: over the limit = rejected
                        .build())
                : null;

        if (circuitBreaker != null) {
            // visible in Prometheus: 0 closed, 1 half open, 2 open (this is what an alert watches)
            metrics.circuitStateGauge(dependency, () -> switch (circuitBreaker.getState()) {
                case CLOSED, DISABLED, METRICS_ONLY -> 0;
                case HALF_OPEN -> 1;
                case OPEN, FORCED_OPEN -> 2;
            });
        }
    }

    // ===================================================================
    // Normal call: waits for the result
    // ===================================================================

    public <T> T call(Supplier<T> action) {
        return call(action, config.timeout());
    }

    /** Same, with a shorter or longer timeout for this one call (for example a quick helper call). */
    public <T> T call(Supplier<T> action, Duration timeout) {
        long startNanos = System.nanoTime();
        try {
            checkProviderRateLimit();
            T result = bulkhead.executeSupplier(() ->
                    retry.executeSupplier(() ->
                            throughCircuitBreaker(() -> runWithTimeout(action, timeout))));
            metrics.modelCall(dependency, "success", System.nanoTime() - startNanos);
            return result;
        } catch (RuntimeException e) {
            metrics.modelCall(dependency, outcomeOf(e), System.nanoTime() - startNanos);
            throw translate(e);
        }
    }

    private <T> T throughCircuitBreaker(Supplier<T> action) {
        return circuitBreaker == null ? action.get() : circuitBreaker.executeSupplier(action);
    }

    /**
     * Runs the action in its own thread and waits at most "timeout" for it.
     * (Virtual threads are very cheap, so one thread per call is fine.)
     */
    private <T> T runWithTimeout(Supplier<T> action, Duration timeout) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();   // keeps request ids in the log lines
        // The call runs in another thread. The snapshot carries the current trace into it, so that the
        // model call shows up as a step INSIDE the trace of the request, and not as a separate trace.
        ContextSnapshot trace = ContextSnapshotFactory.builder().build().captureAll();
        Future<T> future = executor.submit(trace.wrap(() -> {
            if (logContext != null) {
                MDC.setContextMap(logContext);
            }
            try {
                return action.get();
            } finally {
                MDC.clear();
            }
        }));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CallTimeoutException(name, timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiServiceUnavailableException("The " + name + " call was interrupted.", e);
        }
    }

    // ===================================================================
    // Streamed call: the answer arrives word by word
    // ===================================================================

    /**
     * Same protections for a stream. Differences:
     *   - the timeout is for the FIRST word; after that, for the pause between two words
     *   - a retry is only possible while nothing has been sent yet. Once words reached the user,
     *     repeating the call would give a different text, so the error is passed on instead.
     */
    public <T> Flux<T> stream(Supplier<Flux<T>> source) {
        return Flux.defer(() -> {
            try {
                admit();
            } catch (RuntimeException rejected) {
                metrics.modelCall(dependency, outcomeOf(rejected), 0);
                throw rejected;
            }

            long startNanos = System.nanoTime();
            AtomicBoolean anyElementSent = new AtomicBoolean(false);
            AtomicBoolean outcomeRecorded = new AtomicBoolean(false);

            return Flux.defer(source)
                    .doOnNext(element -> anyElementSent.set(true))
                    .timeout(Mono.delay(config.timeout()), element -> Mono.delay(config.streamIdleTimeout()))
                    .retryWhen(reactor.util.retry.Retry
                            .backoff(Math.max(0, config.retryAttempts() - 1), config.retryBackoff())
                            .maxBackoff(Duration.ofSeconds(10))
                            .jitter(0.3)
                            .filter(error -> !anyElementSent.get() && isTransient(error))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .doOnComplete(() -> {
                        recordOutcome(outcomeRecorded, startNanos, null);
                        metrics.modelCall(dependency, "success", System.nanoTime() - startNanos);
                    })
                    .doOnError(error -> {
                        recordOutcome(outcomeRecorded, startNanos, error);
                        metrics.modelCall(dependency, outcomeOf(error), System.nanoTime() - startNanos);
                    })
                    .doOnCancel(() -> {
                        // the user closed the page: neither a success nor a failure of the model
                        if (outcomeRecorded.compareAndSet(false, true) && circuitBreaker != null) {
                            circuitBreaker.releasePermission();
                        }
                    })
                    .doFinally(signal -> bulkhead.onComplete())
                    .onErrorMap(error -> !isAppException(error), error -> translate(error));
        });
    }

    /** The checks before a streamed call starts: rate limit, circuit breaker, free slot. Throws when one says no. */
    private void admit() {
        checkProviderRateLimit();
        if (circuitBreaker != null && !circuitBreaker.tryAcquirePermission()) {
            throw openCircuit(null);
        }
        try {
            bulkhead.acquirePermission();   // waits up to maxWaitForSlot
        } catch (BulkheadFullException e) {
            if (circuitBreaker != null) {
                circuitBreaker.releasePermission();
            }
            throw busy();
        }
    }

    /** The name of what happened, used as a metric tag. */
    private static String outcomeOf(Throwable error) {
        if (error instanceof BulkheadFullException || error instanceof ServiceBusyException) {
            return "busy";
        }
        if (error instanceof CallNotPermittedException
                || (error instanceof AiServiceUnavailableException && error.getCause() == null)) {
            return "circuit_open";
        }
        if (error instanceof CallTimeoutException || error instanceof TimeoutException) {
            return "timeout";
        }
        return "failure";
    }

    private void recordOutcome(AtomicBoolean recorded, long startNanos, Throwable error) {
        if (circuitBreaker == null || !recorded.compareAndSet(false, true)) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        if (error == null) {
            circuitBreaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        } else {
            circuitBreaker.onError(elapsed, TimeUnit.NANOSECONDS, error);
        }
    }

    // ===================================================================
    // Shared helpers
    // ===================================================================

    private void checkProviderRateLimit() {
        if (providerRateLimiter != null && !providerRateLimiter.acquirePermission()) {
            log.warn("Provider rate limit reached for the {} ({} calls per minute)", name, config.callsPerMinute());
            throw busy();
        }
    }

    private ServiceBusyException busy() {
        return new ServiceBusyException("The " + name + " is busy right now.");
    }

    private AiServiceUnavailableException openCircuit(Throwable cause) {
        return new AiServiceUnavailableException(
                "The " + name + " is temporarily unavailable after repeated errors.", cause);
    }

    private boolean isAppException(Throwable error) {
        return error instanceof AiServiceUnavailableException || error instanceof ServiceBusyException;
    }

    /** Turns library exceptions into our own two exceptions, which the rest of the application understands. */
    private RuntimeException translate(Throwable error) {
        if (error instanceof AiServiceUnavailableException || error instanceof ServiceBusyException) {
            return (RuntimeException) error;
        }
        if (error instanceof BulkheadFullException) {
            return busy();
        }
        if (error instanceof CallNotPermittedException) {
            return openCircuit(error);
        }
        if (error instanceof CallTimeoutException || error instanceof TimeoutException) {
            return new AiServiceUnavailableException("The " + name + " took too long to answer.", error);
        }
        return new AiServiceUnavailableException("The " + name + " is not available right now.", error);
    }

    /**
     * Is this failure worth a retry, and does it count against the circuit breaker?
     * Yes for: connection problems, timeouts, server errors (5xx), "too many requests" (429).
     * No for: everything else (for example a 400 or 404, which would fail again).
     */
    static boolean isTransient(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof IOException
                    || current instanceof TimeoutException
                    || current instanceof CallTimeoutException
                    || current instanceof ResourceAccessException            // RestClient: connection or read problem
                    || current instanceof WebClientRequestException          // WebClient (streaming): connection problem
                    || current instanceof HttpServerErrorException           // 5xx
                    || current instanceof TransientAiException) {            // Spring AI's own "may pass" marker
                return true;
            }
            if (current instanceof HttpClientErrorException clientError
                    && clientError.getStatusCode().value() == 429) {         // "too many requests"
                return true;
            }
            if (current instanceof WebClientResponseException response
                    && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    // ----- information for the health check and for tests -----

    public String circuitState() {
        return circuitBreaker == null ? "DISABLED" : circuitBreaker.getState().name();
    }

    public int freeSlots() {
        return bulkhead.getMetrics().getAvailableConcurrentCalls();
    }

    public String name() {
        return name;
    }
}
