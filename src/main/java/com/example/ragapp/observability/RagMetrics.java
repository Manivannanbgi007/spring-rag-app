package com.example.ragapp.observability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * All our own metrics and trace steps in ONE place, so that the names are easy to find and stay consistent.
 *
 * Two tools are used:
 *
 *   OBSERVATION  one piece of code that is timed AND traced. For every observation Micrometer creates
 *                a timer metric (for Prometheus) and a span (for the trace in Jaeger) from the same call.
 *                Used for the steps of a request: rag.chat, rag.stage (embedding, vector_search ...), rag.ingestion.
 *   METER        a plain counter, timer or gauge for something that is not a step: tokens, cost, circuit state ...
 *
 * What is NEVER put into a metric name, a tag or a span attribute: the text of a question, an answer, a
 * document, a user id or anything personal. Tags have a small fixed set of values (a stage name, an outcome,
 * a tenant), because every different tag value creates a new time series that costs memory.
 */
@Component
public class RagMetrics {

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public RagMetrics(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    /** For tests and for code that is created without Spring: it records into memory and nothing is exported. */
    public static RagMetrics forTests() {
        return new RagMetrics(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    public MeterRegistry meters() {
        return meters;
    }

    // ===================================================================
    // Steps of a request (timer + trace span)
    // ===================================================================

    /**
     * Times and traces one step of answering a question. Metric: rag_stage_seconds{stage="..."}.
     * Stages: embedding, vector_search, keyword_search, rerank, rewrite, generation.
     */
    public <T> T stage(String stage, Supplier<T> action) {
        return Observation.createNotStarted("rag.stage", observations)
                .contextualName("rag." + stage)
                .lowCardinalityKeyValue("stage", stage)
                .observe(action);
    }

    /** Same for a streamed step: the timer and span end when the stream ends, fails or is cancelled. */
    public <T> Flux<T> stageStream(String stage, Flux<T> source) {
        return Flux.defer(() -> {
            Observation observation = Observation.createNotStarted("rag.stage", observations)
                    .contextualName("rag." + stage)
                    .lowCardinalityKeyValue("stage", stage)
                    .start();
            return source
                    .doOnError(observation::error)
                    .doFinally(signal -> observation.stop());
        });
    }

    /**
     * A whole chat request. Metric: rag_chat_seconds{mode, outcome, degraded}, so the number of
     * questions, the answer rate and the latency all come from one metric.
     */
    public ChatObservation startChat(String mode) {
        Observation observation = Observation.createNotStarted("rag.chat", observations)
                .contextualName("rag.chat")
                .lowCardinalityKeyValue("mode", mode)
                .lowCardinalityKeyValue("outcome", "error")        // replaced by finish() when the request ends normally
                .lowCardinalityKeyValue("degraded", "false")
                .start();
        return new ChatObservation(observation);
    }

    /** The running observation of one chat request. */
    public static final class ChatObservation {
        private final Observation observation;
        private final AtomicBoolean ended = new AtomicBoolean(false);   // a request ends once, whatever happens

        private ChatObservation(Observation observation) {
            this.observation = observation;
        }

        /**
         * Makes this request the PARENT of every step that starts inside the try block, so that the trace
         * shows the steps nested inside "rag.chat". For code that runs in the calling thread.
         */
        public Observation.Scope open() {
            return observation.openScope();
        }

        /** The same for a stream: every step that runs while the stream runs becomes a child of this request. */
        public <T> Flux<T> inContext(Flux<T> stream) {
            return stream.contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        }

        /** outcome: "answered", "degraded" (limited answer), "not_found" or "error". */
        public void finish(String outcome, boolean degraded) {
            if (ended.compareAndSet(false, true)) {
                observation.lowCardinalityKeyValue("outcome", outcome);
                observation.lowCardinalityKeyValue("degraded", String.valueOf(degraded));
                observation.stop();
            }
        }

        public void fail(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                observation.error(error);
                observation.stop();
            }
        }

        /** The user closed the page before the answer was complete. */
        public void cancelled() {
            if (ended.compareAndSet(false, true)) {
                observation.lowCardinalityKeyValue("outcome", "cancelled");
                observation.stop();
            }
        }
    }

    /** Document processing. Metric: rag_ingestion_seconds{outcome="completed|failed"}. */
    public IngestionObservation startIngestion() {
        Observation observation = Observation.createNotStarted("rag.ingestion", observations)
                .contextualName("rag.ingestion")
                .lowCardinalityKeyValue("outcome", "failed")
                .start();
        return new IngestionObservation(observation);
    }

    /** The running observation of one document that is being processed. */
    public static final class IngestionObservation {
        private final Observation observation;

        private IngestionObservation(Observation observation) {
            this.observation = observation;
        }

        public Observation.Scope open() {
            return observation.openScope();
        }

        public void finish(boolean completed) {
            observation.lowCardinalityKeyValue("outcome", completed ? "completed" : "failed");
            observation.stop();
        }
    }

    // ===================================================================
    // Counters, summaries and gauges
    // ===================================================================

    /** Tokens used. kind = prompt, completion or embedding. Tenants are few and known, so they are a safe tag. */
    public void tokens(String tenantId, String kind, long count) {
        if (count > 0) {
            meters.counter("rag.tokens", "tenant", tenantId, "kind", kind).increment(count);
        }
    }

    /** Estimated cost in US dollars (see app.cost in application.yml). */
    public void cost(String tenantId, double dollars) {
        if (dollars > 0) {
            meters.counter("rag.cost.usd", "tenant", tenantId).increment(dollars);
        }
    }

    /** What the search found for one question. */
    public void retrieval(int returnedChunks, double bestScore, boolean degraded) {
        DistributionSummary.builder("rag.retrieval.chunks").description("Chunks given to the model per question")
                .register(meters).record(returnedChunks);
        if (returnedChunks > 0) {
            DistributionSummary.builder("rag.retrieval.best.score").description("Score of the best chunk (0 to 1)")
                    .register(meters).record(bestScore);
        }
        if (returnedChunks == 0) {
            meters.counter("rag.retrieval.empty").increment();
        }
        if (degraded) {
            meters.counter("rag.retrieval.degraded").increment();
        }
    }

    /** An answer that was thrown away because the documents do not support it. */
    public void answerRejected() {
        meters.counter("rag.answer.rejected").increment();
    }

    /** Sentences removed from document text because they looked like orders to the model. */
    public void promptInjectionRemoved(int sentences) {
        if (sentences > 0) {
            meters.counter("rag.prompt.injection.removed").increment(sentences);
        }
    }

    /** Sensitive values replaced by placeholders before a model call. */
    public void piiMasked(int values) {
        if (values > 0) {
            meters.counter("rag.pii.masked").increment(values);
        }
    }

    public void feedback(String rating, boolean reportedIncorrect) {
        meters.counter("rag.feedback", "rating", rating, "reported_incorrect", String.valueOf(reportedIncorrect))
                .increment();
    }

    /** scope = user, tenant or upload */
    public void rateLimited(String scope) {
        meters.counter("rag.ratelimit.rejected", "scope", scope).increment();
    }

    /**
     * One call to a model, as seen by ProtectedCall. outcome = success, failure, timeout, busy or circuit_open.
     * This is how a rising error rate of a provider shows up.
     */
    public void modelCall(String dependency, String outcome, long nanos) {
        Timer.builder("rag.model.call").description("Calls to a model, including retries")
                .tag("dependency", dependency).tag("outcome", outcome)
                .register(meters).record(Duration.ofNanos(nanos));
    }

    /** 0 = CLOSED (normal), 1 = HALF_OPEN (testing), 2 = OPEN (calls refused). */
    public void circuitStateGauge(String dependency, Supplier<Number> state) {
        Gauge.builder("rag.circuit.state", state, supplier -> supplier.get().doubleValue())
                .description("Circuit breaker: 0 closed, 1 half open, 2 open")
                .tag("dependency", dependency)
                .strongReference(true)   // without this the gauge only holds the function weakly, and it can be garbage collected
                .register(meters);
    }
}
