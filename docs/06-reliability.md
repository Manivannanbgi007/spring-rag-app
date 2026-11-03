# Reliability: what happens when something is slow or broken

Every call to a model goes through one class, `ProtectedCall`, with five protections:

| Protection | What it does | Why |
|---|---|---|
| **Rate limit** (provider) | at most N model calls per minute for everybody together (`calls-per-minute`) | protects a paid provider and its bill |
| **Concurrency limit** | at most 4 answers at the same time; a 5th waits up to 10 s, then gets "busy" | a CPU model gets slower for everybody when more run at once |
| **Retry with backoff** | a failed try is repeated after 1 s, 2 s, 4 s ... (with a little random jitter) | short hiccups pass by themselves |
| **Circuit breaker** | when half of the last calls failed, calls fail **immediately** for 30 s, then 2 test calls decide | a broken model must not make every user wait, and gets time to recover |
| **Timeout** | a try that takes too long is given up | a hanging model must not hang users |

Only *transient* failures are retried and counted by the circuit breaker: refused connection, timeout, HTTP 5xx, HTTP 429.
A wrong request (HTTP 400, 404) would fail again, so it is not retried. Streamed answers can only be retried **before the
first word** reached the user; after that a retry would produce a different text.

Each dependency has its own limits (`app.resilience` in `application.yml`):

| | timeout | tries | max parallel | circuit breaker |
|---|---|---|---|---|
| language model (answers) | 90 s (streamed: first word; 30 s between words) | 2 | 4 | yes |
| embedding model (the question) | 10 s | 3 | 8 | yes |
| embedding model (document processing) | 120 s | 3 | 2 | no: a failing document just ends `FAILED` |
| database search | 5 s (query timeout) | - | - | - |

Reranking has no timeout because it is a few lines of Java without any external call. If it is ever replaced by a model
call, give it its own `ProtectedCall`.

## Graceful degradation: a limited answer is better than an error

| What is down | What the user gets |
|---|---|
| **language model** | no written answer, but the **closest passages** from the documents with document and page (`degraded: true`) |
| **embedding model** | the search continues with **keywords only** (`degraded: true`); the answer is still written if the language model works |
| **both** (for example Ollama stopped) | the closest passages, found by keywords |
| **database** | a clear `503` with `Retry-After`; nothing can replace the documents |
| rewrite of a follow-up question fails | the original question is used |
| too busy, or circuit open | `503` with `Retry-After` (when no passages can be shown) |

The chat page shows a "Limited answer" notice when `degraded` is true. `GET /actuator/health` shows the state of every
circuit breaker (`CLOSED` normal, `OPEN` calls refused for a while, `HALF_OPEN` testing) and the free call slots.
The status stays `UP` on purpose: "degraded" does not mean "this application is broken".

**Try it yourself** (the documents must have been processed before):

```
docker stop rag-ollama
```

Ask a question: you get the closest passages after about 2 seconds, and immediately while the circuit is open.

```
docker start rag-ollama
```

After the open time (20 to 30 seconds) the circuit tests the model again and normal answers return.
(The first answer after a start is slow: Ollama loads the model into memory.)

## Limits per user and per tenant, and API protection

| Limit | Default | Result |
|---|---|---|
| questions per user per minute | 20 | `429 Too Many Requests` with `Retry-After: 60` |
| questions per tenant per minute (all its users) | 120 | `429` |
| document uploads per tenant per hour | 30 | `429` |
| JSON request size | 64 KB | `413` (checked before the body is read) |
| upload size | 100 MB | `413` |

Set a limit to `0` to switch it off (`app.rate-limit`). The counters live in the memory of this server; with several servers
they would have to live in a shared store such as Redis. Other websites cannot call the API from a browser unless they are
listed in `app.cors.allowed-origins` (empty by default). A client that sends a JSON body without `Content-Length`
(chunked) is not covered by the size check.

## Caching

- **Cached:** the vector of a question (1000 entries, 1 hour). The same text always gives the same vector, and calculating it is a model call. The text is masked for personal data before it is cached, and the cache lives in memory only.
- **Not cached, on purpose:** answers (they depend on documents that can change at any moment, and a repeated *wrong* answer would stay wrong) and document lookups (one small indexed query).

## Findings that shaped this phase

- **Spring AI has a hidden retry.** By default it retries failed model calls up to 10 times with pauses of 2 s, 10 s, 50 s ... In a test with a model that returns HTTP 500 immediately, every try took 1 s (our timeout) instead of a few milliseconds, because Spring AI was sleeping inside its own retry. It is switched off with `spring.ai.retry.max-attempts: 0` (in Spring AI 2.0 the number counts the retries *after* the first try, so `1` still means one hidden retry). Now exactly our own retries happen, and a failure is seen in about 100 ms.
- **The circuit counts every try, not every question.** With 2 tries per question, two failed questions are enough to open a circuit that needs 4 calls. That is intended: it reacts fast. After an outage the circuit stays open until its open time has passed, even if the model is already back.
- **Spring web errors were reported as `500`.** A request to an unknown address, with the wrong method or with a malformed id fell into the catch-all handler and answered "500 Something went wrong". They are now `404`, `405`, `415` and `400`.

## How the failure tests work

`FakeOllamaServer` (in the tests) is a pretend Ollama that can answer normally, return errors, answer too slowly, or fail
only the next N requests. `ModelOutageTest` runs the real application against it and tells the story: normal answer,
language model breaks, circuit opens, embedding breaks too, both recover, a slow model times out, streaming falls back.
`IngestionResilienceTest` checks that a short hiccup during document processing is retried **without duplicate chunks**, and that
a real outage ends with a clear `FAILED` message and no half-saved chunks. Limits are made tiny in those tests so that they run in seconds.
