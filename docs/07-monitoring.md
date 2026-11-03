# Monitoring: metrics, traces, logs, cost and alerts

Four tools answer four different questions:

| Tool | Question it answers | Where |
|---|---|---|
| **Metrics** (numbers over time) | Is it healthy? How many questions, how fast, how many errors, what does it cost? | `GET /actuator/prometheus`, Prometheus |
| **Traces** (one request, step by step) | Why was THIS question slow? | Jaeger |
| **Logs** (events) | What happened, in order? | the console, or JSON lines |
| **Usage report** (money and tokens per tenant) | Who uses how much? | `GET /api/usage` |

## Start the monitoring tools (optional)

```
docker compose --profile monitoring up -d
```

```
set TRACING_EXPORT=true
mvnw spring-boot:run
```

| Tool | Address |
|---|---|
| Prometheus (metrics, alerts) | http://localhost:9190 |
| Jaeger (traces) | http://localhost:16690 (pick the service `spring-rag-app`) |

The application always creates traces (the `traceId` is in every log line), but it only SENDS them to Jaeger when
`TRACING_EXPORT=true`. By default every request is traced (`TRACING_SAMPLING=1.0`); in production use 0.1 or less.

## What is measured

Everything we added is defined in one class, `RagMetrics`, so the names are easy to find. The important ones
(Prometheus writes `.` as `_`, and a timer ends in `_seconds`):

| Metric | What it tells |
|---|---|
| `rag_chat_seconds{mode, outcome, degraded}` | **Questions.** Count = number of questions, `outcome` = answered / not_found / degraded / error, the buckets give the latency (p95) |
| `rag_stage_seconds{stage}` | Time of each step: `embedding`, `vector_search`, `keyword_search`, `rerank`, `rewrite`, `generation` |
| `rag_tokens_total{tenant, kind}` | Tokens used: `prompt`, `completion`, `embedding` |
| `rag_cost_usd_total{tenant}` | Estimated cost (see below) |
| `rag_model_call_seconds{dependency, outcome}` | Calls to a model: `success`, `failure`, `timeout`, `busy`, `circuit_open`. A rising error rate shows up here |
| `rag_circuit_state{dependency}` | 0 = closed (normal), 1 = half open, 2 = **open** |
| `rag_retrieval_chunks`, `rag_retrieval_best_score` | What the search returns: how many chunks, how good the best one is. `rag_retrieval_empty_total`, `rag_retrieval_degraded_total` |
| `rag_answer_rejected_total` | Answers thrown away because the documents do not support them |
| `rag_prompt_injection_removed_total` | Order-like sentences removed from documents |
| `rag_pii_masked_total` | Sensitive values masked before a model call |
| `rag_feedback_total{rating, reported_incorrect}` | Thumbs up/down and "incorrect" reports |
| `rag_ratelimit_rejected_total{scope}` | Requests refused by the rate limits |
| `rag_ingestion_seconds{outcome}` | Documents processed: `completed` or `failed`, and how long it took |
| `cache_gets_total{cache="embedding", result}` | Hit rate of the embedding cache |
| `gen_ai_client_token_usage` | Spring AI's own token counter (all calls, including document processing) |
| `http_server_requests_seconds` | Standard web metrics |

**Why only a few tags?** Every different tag value creates a new time series that costs memory. So tags only have a small,
fixed set of values: a stage name, an outcome, a tenant. **Never** a user id, a question or a document name.

## One trace per question

In Jaeger one question looks like this (real example, times in milliseconds):

```
http post /api/chat/stream                 1951
  rag.chat   mode=stream outcome=answered
    rag.rewrite                            1089    <- the follow-up question is rewritten
      chat llama3.2:3b   input_tokens=325 output_tokens=8
        http post /api/chat
    rag.embedding                            32
      embedding   input_tokens=10
        http post /api/embed
    rag.vector_search                         3
    rag.keyword_search                        3
    rag.rerank                                1
    rag.generation                          758    <- the language model writes the answer
      chat llama3.2:3b   input_tokens=708 output_tokens=7
```

One glance shows where the time went (almost always the model), what was called, and how many tokens each call used.
The trace follows the request into other threads (model calls run in their own thread, document processing runs in the
background), so it does not break into separate pieces.

## Logs

Normal logs are readable text with the request id in every line. For a log system (Elasticsearch, Loki, Splunk ...) switch
to **JSON lines**: one JSON object per log line, so logs can be searched and counted by field:

```
mvnw spring-boot:run -Dspring-boot.run.profiles=json
```

For every question ONE summary line is written, with everything an operator needs:

```json
{"message":"Chat request completed: outcome=answered ...", "tenant":"default", "user":"895d2e81",
 "conversationId":"abfad452-...", "outcome":"answered", "degraded":false, "sources":1, "durationMs":1378,
 "promptTokens":716, "completionTokens":7, "embeddingTokens":18, "costUsd":0.0,
 "requestId":"48cb33d9", "traceId":"4658253c...", "spanId":"896972aa..."}
```

The `traceId` in the log line is the id of the trace in Jaeger, so a slow request in the log can be opened as a trace.

## Privacy of the telemetry

Logs, metrics and traces are copied to other systems and kept for a long time, so they must never contain personal data:

- **No** question, answer, prompt, document text, email or user id. The user appears only as a short one-way hash (`895d2e81`), which shows "the same user" without naming the person.
- Spring AI can write prompts and answers into traces. That is switched **off** (`spring.ai.chat.observations.log-prompt: false` and the others in `application.yml`) and written down explicitly, so nobody switches it on by accident.
- The table `usage_records` has numbers and the tenant only, so the cost history can be kept after conversations are deleted.
- A test (`ObservabilityTest`) asks a question with an email address and a rare marker word and checks that neither appears in any log line, metric or the Prometheus output. A check of the real traces in Jaeger found no question text either.

## Tokens and cost

Every answer reports what it used:

```json
"usage": {"promptTokens": 707, "completionTokens": 7, "embeddingTokens": 8, "estimatedCostUsd": 0.0}
```

The models run on your computer, so the real price is 0 and `app.cost` is set to 0. If you switch to a cloud model, enter its
prices per 1000 tokens (`llm-input-per-1k`, `llm-output-per-1k`, `embedding-per-1k`) and the cost per question, per day
and per tenant appears. A repeated question costs no embedding tokens, because its vector comes from the cache.

```
curl -H "X-Tenant-Id: default" "http://localhost:8110/api/usage?days=7"
```

Returns the questions, how many were answered / not found / limited, the tokens, the estimated cost, the average and p95 speed,
and the same numbers per day. (There is no login yet, so everybody who knows the tenant can read it. Limit it to
administrators when authentication is added.) Tokens of document processing are not counted per tenant; Spring AI's own
metric `gen_ai_client_token_usage` includes them in the total.

## Alerts

`monitoring/alert-rules.yml` has 13 rules, checked by Prometheus every 15 seconds (see http://localhost:9190/alerts):

| Alert | Fires when |
|---|---|
| `ApplicationDown` | Prometheus cannot reach the application for 1 minute |
| `ModelProviderFailing` | more than 20% of the calls to a model fail or time out (5 minutes) |
| `CircuitBreakerOpen` | a circuit breaker is open for 1 minute: users get limited answers |
| `DegradedAnswers` | users received limited answers for 5 minutes |
| `SlowAnswers` / `SlowEmbedding` | p95 of a question above 30 s / of the embedding above 5 s |
| `CostSpike` / `TokenUseSpike` | more than 5 dollars in an hour / 5 times the daily token average |
| `IngestionFailures` | a document failed to process |
| `ManyQuestionsNotAnswered` | more than half of the questions get "not found" (documents missing, or the search is broken?) |
| `AnswersRejectedAsUnsupported` | many answers rejected because the documents do not support them (hallucination or attack) |
| `PromptInjectionAttempts` | order-like sentences were found in documents |
| `ManyRateLimitedRequests` | many requests refused by the rate limits |

The rules are checked with `promtool check rules` and load in Prometheus. **Sending** alerts to e-mail or chat needs one more
component (Alertmanager) and an address to send to; it is not part of this project. The thresholds are starting points:
look at a week of real traffic and adjust them.
