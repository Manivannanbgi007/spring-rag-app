package com.example.ragapp.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.ragapp.common.AiServiceUnavailableException;
import com.example.ragapp.common.ServiceBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

/** No Spring and no model: fake calls with tiny timeouts, so the whole class runs in a few seconds. */
class ProtectedCallTest {

    // ----- helpers -----

    /** Builds a policy. Durations are in milliseconds so that the tests stay fast. */
    private PolicyProperties policy(int timeoutMs, int maxConcurrent, int maxWaitMs, int attempts, int backoffMs,
                                    boolean breaker, int minCalls, int window, int openMs, int callsPerMinute) {
        return new PolicyProperties(Duration.ofMillis(timeoutMs), Duration.ofMillis(timeoutMs), maxConcurrent,
                Duration.ofMillis(maxWaitMs), attempts, Duration.ofMillis(backoffMs), breaker, 50, minCalls, window,
                Duration.ofMillis(openMs), callsPerMinute);
    }

    /** Simple policy: 1 second timeout, 4 parallel calls, the given number of tries, no breaker, no rate limit. */
    private ProtectedCall simple(int attempts) {
        return new ProtectedCall("test model", policy(1000, 4, 100, attempts, 50, false, 4, 10, 1000, 0));
    }

    private RuntimeException transientError() {
        return new ResourceAccessException("connection refused");
    }

    // ----- normal call -----

    @Test
    void returnsTheResult() {
        assertThat(simple(1).call(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void retriesATransientFailureAndThenSucceeds() {
        AtomicInteger tries = new AtomicInteger();

        String result = simple(3).call(() -> {
            if (tries.incrementAndGet() < 3) {
                throw transientError();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tries).hasValue(3);
    }

    @Test
    void givesUpAfterTheLastTryAndReportsUnavailable() {
        AtomicInteger tries = new AtomicInteger();

        assertThatThrownBy(() -> simple(3).call(() -> {
            tries.incrementAndGet();
            throw transientError();
        })).isInstanceOf(AiServiceUnavailableException.class).hasMessageContaining("test model");

        assertThat(tries).hasValue(3);
    }

    @Test
    void doesNotRetryAWrongRequest() {
        AtomicInteger tries = new AtomicInteger();

        assertThatThrownBy(() -> simple(3).call(() -> {
            tries.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.NOT_FOUND);   // for example "model not found"
        })).isInstanceOf(AiServiceUnavailableException.class);

        assertThat(tries).hasValue(1);   // it would fail again, so it is not repeated
    }

    @Test
    void waitsLongerBeforeEveryRetry() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> new ProtectedCall("test model", policy(1000, 4, 100, 3, 100, false, 4, 10, 1000, 0))
                .call(() -> {
                    throw transientError();
                })).isInstanceOf(AiServiceUnavailableException.class);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(200);   // about 100 ms + about 200 ms (random +-30%)
    }

    // ----- timeout -----

    @Test
    void aCallThatTakesTooLongIsStopped() {
        ProtectedCall call = new ProtectedCall("test model", policy(100, 4, 100, 1, 50, false, 4, 10, 1000, 0));
        long start = System.nanoTime();

        assertThatThrownBy(() -> call.call(() -> {
            sleep(1500);
            return "too late";
        })).isInstanceOf(AiServiceUnavailableException.class).hasMessageContaining("took too long");

        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(800);
    }

    @Test
    void aSlowCallCanGetAShorterTimeoutJustForItself() {
        ProtectedCall call = simple(1);   // normal timeout 1 second

        assertThatThrownBy(() -> call.call(() -> {
            sleep(600);
            return "x";
        }, Duration.ofMillis(100))).isInstanceOf(AiServiceUnavailableException.class);
    }

    // ----- circuit breaker -----

    @Test
    void circuitOpensAfterRepeatedFailuresAndThenFailsImmediately() {
        ProtectedCall call = new ProtectedCall("test model", policy(1000, 4, 100, 1, 50, true, 3, 4, 300, 0));
        AtomicInteger invocations = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> call.call(() -> {
                invocations.incrementAndGet();
                throw transientError();
            })).isInstanceOf(AiServiceUnavailableException.class);
        }
        assertThat(call.circuitState()).isEqualTo("OPEN");

        long start = System.nanoTime();
        assertThatThrownBy(() -> call.call(() -> {
            invocations.incrementAndGet();
            return "never runs";
        })).isInstanceOf(AiServiceUnavailableException.class).hasMessageContaining("temporarily unavailable");

        assertThat(invocations).hasValue(3);   // the 4th call never reached the model
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(100);
    }

    @Test
    void circuitTestsTheModelAgainAfterTheWaitAndClosesWhenItWorks() {
        ProtectedCall call = new ProtectedCall("test model", policy(1000, 4, 100, 1, 50, true, 3, 4, 200, 0));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> call.call(() -> {
                throw transientError();
            })).isInstanceOf(AiServiceUnavailableException.class);
        }
        assertThat(call.circuitState()).isEqualTo("OPEN");

        sleep(300);   // longer than open-duration
        assertThat(call.call(() -> "back")).isEqualTo("back");
        assertThat(call.call(() -> "back")).isEqualTo("back");

        assertThat(call.circuitState()).isEqualTo("CLOSED");
    }

    @Test
    void wrongRequestsDoNotOpenTheCircuit() {
        ProtectedCall call = new ProtectedCall("test model", policy(1000, 4, 100, 1, 50, true, 3, 4, 300, 0));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> call.call(() -> {
                throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
            })).isInstanceOf(AiServiceUnavailableException.class);
        }

        assertThat(call.circuitState()).isEqualTo("CLOSED");   // the model is fine, the requests were wrong
    }

    // ----- concurrency limit and rate limit -----

    @Test
    void tooManyCallsAtTheSameTimeAreRejectedAsBusy() throws Exception {
        ProtectedCall call = new ProtectedCall("test model", policy(5000, 1, 50, 1, 50, false, 4, 10, 1000, 0));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> call.call(() -> {
            started.countDown();
            await(release);
            return "first";
        }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> call.call(() -> "second")).isInstanceOf(ServiceBusyException.class);

        release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(call.call(() -> "third")).isEqualTo("third");   // the slot is free again
    }

    @Test
    void providerRateLimitRejectsCallsOverTheLimit() {
        ProtectedCall call = new ProtectedCall("test model", policy(1000, 4, 100, 1, 50, false, 4, 10, 1000, 2));

        assertThat(call.call(() -> "1")).isEqualTo("1");
        assertThat(call.call(() -> "2")).isEqualTo("2");
        assertThatThrownBy(() -> call.call(() -> "3")).isInstanceOf(ServiceBusyException.class);
    }

    // ----- streaming -----

    @Test
    void streamPassesTheWordsThrough() {
        List<String> words = simple(1).stream(() -> Flux.just("a", "b", "c")).collectList().block(Duration.ofSeconds(2));

        assertThat(words).containsExactly("a", "b", "c");
    }

    @Test
    void streamRetriesWhileNothingHasBeenSentYet() {
        AtomicInteger subscriptions = new AtomicInteger();

        List<String> words = simple(3).stream(() -> Flux.defer(() -> {
            if (subscriptions.incrementAndGet() < 3) {
                return Flux.error(transientError());
            }
            return Flux.just("a", "b");
        })).collectList().block(Duration.ofSeconds(5));

        assertThat(words).containsExactly("a", "b");
        assertThat(subscriptions).hasValue(3);
    }

    @Test
    void streamDoesNotRetryAfterWordsWereSent() {
        AtomicInteger subscriptions = new AtomicInteger();
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        assertThatThrownBy(() -> simple(3).stream(() -> Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.concat(Flux.just("a"), Flux.<String>error(transientError()));
        })).doOnNext(received::add).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(AiServiceUnavailableException.class);

        assertThat(subscriptions).hasValue(1);       // repeating would give a different text
        assertThat(received).containsExactly("a");   // what was already sent stays sent
    }

    @Test
    void streamThatNeverStartsIsStoppedByTheTimeout() {
        ProtectedCall call = new ProtectedCall("test model", policy(150, 4, 100, 1, 50, false, 4, 10, 1000, 0));

        assertThatThrownBy(() -> call.stream(Flux::<String>never).collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(AiServiceUnavailableException.class).hasMessageContaining("took too long");
    }

    @Test
    void streamGivesBackItsSlotWhenItEndsOrIsCancelled() {
        ProtectedCall call = new ProtectedCall("test model", policy(1000, 2, 100, 1, 50, false, 4, 10, 1000, 0));

        call.stream(() -> Flux.just("a")).collectList().block(Duration.ofSeconds(2));
        assertThat(call.freeSlots()).isEqualTo(2);

        call.stream(() -> Flux.just("a", "b", "c")).take(1).collectList().block(Duration.ofSeconds(2));   // cancelled early
        assertThat(call.freeSlots()).isEqualTo(2);

        assertThatThrownBy(() -> call.stream(() -> Flux.<String>error(transientError())).collectList()
                .block(Duration.ofSeconds(2))).isInstanceOf(AiServiceUnavailableException.class);
        assertThat(call.freeSlots()).isEqualTo(2);
    }

    @Test
    void streamIsRejectedWhenTheCircuitIsOpen() {
        ProtectedCall call = new ProtectedCall("test model", policy(1000, 4, 100, 1, 50, true, 3, 4, 5000, 0));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> call.stream(() -> Flux.<String>error(transientError())).collectList()
                    .block(Duration.ofSeconds(2))).isInstanceOf(AiServiceUnavailableException.class);
        }
        assertThat(call.circuitState()).isEqualTo("OPEN");

        assertThatThrownBy(() -> call.stream(() -> Flux.just("x")).collectList().block(Duration.ofSeconds(2)))
                .isInstanceOf(AiServiceUnavailableException.class).hasMessageContaining("temporarily unavailable");
    }

    // ----- which failures count as transient -----

    @Test
    void transientFailuresAreRecognisedAlsoWhenWrapped() {
        assertThat(ProtectedCall.isTransient(new RuntimeException("outer", new ConnectException("refused")))).isTrue();
        assertThat(ProtectedCall.isTransient(transientError())).isTrue();
        assertThat(ProtectedCall.isTransient(new HttpServerErrorException(HttpStatus.BAD_GATEWAY))).isTrue();
        assertThat(ProtectedCall.isTransient(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isTrue();
        assertThat(ProtectedCall.isTransient(new CallTimeoutException("x", Duration.ofSeconds(1)))).isTrue();
    }

    @Test
    void wrongRequestsAreNotTransient() {
        assertThat(ProtectedCall.isTransient(new HttpClientErrorException(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(ProtectedCall.isTransient(new HttpClientErrorException(HttpStatus.NOT_FOUND))).isFalse();
        assertThat(ProtectedCall.isTransient(new IllegalArgumentException("bad"))).isFalse();
    }

    // ----- small helpers -----

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
