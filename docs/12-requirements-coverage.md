# Requirements coverage: what is done, what is partly done, what is not done

This table follows your requirements list one by one. It is meant to be honest: **"Partly"** and **"Not done"** are listed on purpose, with the reason and
where the feature would be added. Status: **Done** = implemented and covered by a test or a live check. **Partly** = a useful part exists. **Not done** = not implemented.

## The application

| Requirement | Status | Where / note |
|---|---|---|
| Runs locally from the command prompt | Done | `mvnw spring-boot:run`, [Setup](03-setup-run-test.md) |
| Docker Compose for all infrastructure | Done | PostgreSQL + pgvector, Ollama and its model download, optional Prometheus and Jaeger |
| Clean, modular structure | Done | packages by responsibility, [Project layout](10-extending-and-production.md) |
| Step-by-step instructions (setup, run, test) | Done | [Setup](03-setup-run-test.md) |
| Sample HRM documents, PDF and DOCX, up to 500 pages | Done | 8 policies (4 PDF, 4 DOCX) and one 500-page PDF, [Sample documents](09-sample-documents.md) |
| Upload PDF and DOCX | Done | [API](04-api-reference.md) |
| Extract and process the content | Done | [How it works](01-how-it-works.md) |
| Store in a form suitable for retrieval | Done | pgvector (HNSW, cosine) + full-text index |
| Chat interface | Done | `http://localhost:8110/` and the API (JSON and streaming) |
| Grounded answers, source references (document, page) | Done | PDF: file and page. DOCX: file and section. Plus the sentence the answer is based on |
| Clear "cannot be found" instead of inventing | Done | 9 of 9 unanswerable evaluation questions refused, plus 3 on the sample documents |
| Handle documents up to 500 pages | Done | the 500-page handbook is processed in the background, see [Sample documents](09-sample-documents.md) for the measured time |
| Validation, exception handling, logging, status tracking | Done | `QUEUED`, `PARSING`, `EMBEDDING`, `COMPLETED`, `FAILED` + message; one error format; request ids in every log line |
| Explain the workflow and why each technology | Done | [How it works](01-how-it-works.md), [Technology choices](02-technology-choices.md) |
| Extendable to other document types and more policies | Done | [Extending](10-extending-and-production.md); only PDF and DOCX parsers exist today |

## A. Spring AI and LLM integration

| Requirement | Status | Note |
|---|---|---|
| `ChatClient`, `ChatModel`, `EmbeddingModel` | Done | used in the question flow, the rewrite step and ingestion |
| **Advisors** | **Not used** | on purpose: the pipeline is explicit (`RetrievalService`, `ContextBuilder`, `RagChatService`) because hybrid search, masking and citation checks sit between the steps. See [Technology choices](02-technology-choices.md) |
| Provider abstraction (OpenAI, Azure OpenAI, local) | **Partly** | local models (Ollama) are wired and tested. The code uses the neutral interfaces, but **no cloud provider was configured or tested**. Steps are in [Extending](10-extending-and-production.md) |
| Prompt engineering, templates, context injection | Done | `prompts/*.st`: system prompt, user prompt, rewrite prompt with examples |
| **Structured output** (answer as a Java record) | **Not done** | answers are text. The API returns a record (`ChatResponse`) built by the application, not by the model. `.entity(...)` would be the way |
| **Function / tool calling** | **Not done** | the model cannot call application functions. It needs real user identity first |
| Model configuration (temperature, token limits, timeouts, model selection) | Done | `application.yml`, `.env`, [Reliability](06-reliability.md) |
| Streaming responses | Done | server-sent events (`/api/chat/stream`, a reactive `Flux`), with masking restored on the fly |

## B. RAG pipeline

| Requirement | Status | Note |
|---|---|---|
| Ingest PDF, DOCX | Done | |
| **HTML, Markdown, CSV and other formats** | **Not done** | extension point exists (`DocumentParser`) |
| Tables and metadata | **Partly** | DOCX tables become text rows. PDF tables are read as text lines (layout may be lost) |
| **Scanned PDFs and OCR** | **Not done** | a PDF without text ends `FAILED` with a clear message |
| Chunking: size, document-aware splitting | Done | about 500 tokens, cut per page (PDF) and per heading (DOCX), preferring sentence ends |
| Chunk **overlap**, semantic splitting | **Not done** | chunks do not overlap. The size comparison is in [Evaluation](08-evaluation.md) |
| Metadata enrichment | **Partly** | document id, tenant, file name, title, version, page or section, chunk number. Creation time is on the `documents` row, not in each chunk. **No permissions metadata** |
| Embeddings of chunks and questions | Done | |
| Vector storage with pgvector, cosine similarity | Done | |
| Hybrid search | Done | vector + PostgreSQL full-text, fused with Reciprocal Rank Fusion |
| Reranking | Done | score from similarity and word coverage, in Java. **Not a dedicated reranking model** (cross-encoder) |
| Context construction (duplicates, size limit, order) | Done | |
| Grounded answers, acknowledging missing information | Done | |
| Source citations | Done | |

## C. Security and access control (Spring Security was explicitly out of scope)

| Requirement | Status | Note |
|---|---|---|
| **Document-level authorization** | **Not done** | access is by **tenant** only; every user of a tenant can search all its documents |
| Tenant isolation in every retrieval path | Done | the tenant filter is in both searches (vector and keyword) and in every document and conversation query; tests cover it |
| Prompt injection defense | Done (reduces the risk) | removal of order-like sentences, system prompt, rejection of unsupported answers. A pattern list cannot catch everything |
| PII and secret masking before model calls | Done | email, phone, card (checksum), ID numbers, passwords and keys. **Names and addresses are not found** |
| CORS, rate limits, input validation, request-size limits | Done | |
| Secret management | **Partly** | settings come from environment variables and `.env` (not in git). No secrets manager |
| **Authentication** | **Not done** | on purpose. The user comes from the header `X-User-Id`: **it can be forged**. Only `CallerArgumentResolver` would change |

## D. Conversation and application management

| Requirement | Status | Note |
|---|---|---|
| Chat memory, persistence, ownership | Done | conversations and messages in PostgreSQL, owner = tenant + user header |
| Memory versus RAG | Done | memory only for follow-ups, never as evidence |
| Follow-up questions | **Partly** | the question is rewritten with the last 6 messages. The 3B model sometimes fails on very vague references ("and what do I get for it?"); [Evaluation](08-evaluation.md) found and fixed one real case |
| Session expiration | Done | idle longer than 8 hours: `410 Gone`. Ownership is only as strong as the user header (see Authentication) |
| Feedback (rating, report incorrect) | Done | |
| Retention, deletion, export | Done | nightly retention job, `DELETE`, JSON export |

## E. Reliability and performance

| Requirement | Status | Note |
|---|---|---|
| Timeouts per dependency | **Partly** | separate limits for the language model, embedding (question and ingestion) and the database search. **Reranking has none** because it is plain Java without an external call |
| Retries with backoff | Done | only transient errors; streaming only before the first word |
| Circuit breakers | Done | language model and embedding model |
| Rate limiting: user, tenant, provider | Done | |
| Concurrency control: model calls and ingestion | Done | |
| Caching | **Partly** | embeddings of questions are cached. **Not cached on purpose:** answers and document lookups ([Reliability](06-reliability.md) explains why) |
| Asynchronous ingestion | Done | |
| Idempotent ingestion | Done | deterministic chunk ids, tested |
| Flyway migrations | Done | `V1` to `V7` |
| Graceful degradation | Done | closest passages when the model is down, keyword-only search when embedding is down |

## F. Evaluation and answer quality

| Requirement | Status | Note |
|---|---|---|
| Retrieval evaluation (hit@k, MRR) | Done | |
| Groundedness evaluation | Done | word-based check per sentence, plus a judge model as a second opinion |
| **Answer relevance** | **Partly** | measured through expected facts and correct source. There is no separate "does it address the question" score |
| Hallucination handling | Done | |
| Regression dataset (facts, unanswerable questions) | Done | 41 questions, plus 24 on the sample documents |
| LLM-as-a-judge, validated against a human | Done | 50% agreement with the first prompt, 88% with examples. Used as a second signal, not a gate |
| A/B tests | **Partly** | search strategy, chunk size, similarity threshold and prompt were compared. **Embedding models were not compared** (the procedure is written down) |
| Quality thresholds before release | Done | `QualityGates` |

## G. Monitoring and observability

| Requirement | Status | Note |
|---|---|---|
| Actuator, Micrometer metrics | Done | |
| Distributed tracing | Done | Jaeger, opt-in with `TRACING_EXPORT=true` |
| Structured logging with request and conversation ids | Done | profile `json` |
| Cost monitoring per request and tenant | Done | prices are 0 because the models are local; enter real prices when you switch |
| Retrieval diagnostics | Done | counts and scores in the log and in metrics |
| Alerting | **Partly** | 13 rules, validated with `promtool`. **Nothing is sent** to e-mail or chat: that needs Alertmanager and an address |
| Privacy-aware telemetry | Done | a test checks that no question text or personal data reaches logs or metrics |

## H. Deployment and DevOps

| Requirement | Status | Note |
|---|---|---|
| Docker for the dependencies | Done | `docker-compose.yml`, pinned image versions, health checks, volumes |
| **Docker image of the application itself** | **Not done** | the specification asks to run the application from the command prompt. A `Dockerfile` is listed in the [production checklist](10-extending-and-production.md) |
| CI/CD, Kubernetes, cloud deployment | **Not done** | the text of the specification that was received ended at the Docker item |

## What was measured, in short

| Check | Result |
|---|---|
| default tests (`mvnw test`) | 201 tests, all green |
| regression questions with the real model (`AnswerEvaluationTest`) | 41 of 41, all quality gates met |
| the 500-page handbook | processed in about 108 seconds (542 chunks) on a CPU |
| the 24 sample questions on the nine sample documents (`SampleDocsEvaluationTest`) | search 21 of 21; answers 23 of 24 without a problem (one correct answer cited the wrong document), see [Sample documents](09-sample-documents.md) |

The small evaluation handbook is easy, so scores of 100% prove that nothing is broken, not that the system is perfect. Test with your own documents.
