# Technology choices: what is used and why

The rule for every choice: **the simplest tool that does the job, and one that a junior developer can read about in an afternoon.**
The versions below are the ones in `pom.xml` and `docker-compose.yml`.

## Summary

| Need | Choice | Why this and not something else |
|---|---|---|
| Application framework | **Spring Boot 4.1** (Java 21, Maven wrapper) | Standard in many companies, Spring AI is built for it, `mvnw` needs no installed Maven |
| AI integration | **Spring AI 2.0** | One API (`ChatClient`, `EmbeddingModel`, `VectorStore`) for many providers: changing the model is configuration, not a rewrite |
| Language model | **Ollama + llama3.2:3b** | Runs on a laptop CPU, free, and **no document leaves your computer**. Good enough to show the whole system; a bigger model gives better wording |
| Embedding model | **Ollama + nomic-embed-text** (768 numbers per chunk) | Small, fast, good quality for English, same runtime as the language model |
| Vector database | **PostgreSQL 17 + pgvector 0.8** | One database for documents, conversations, usage **and** vectors: one thing to run, back up and secure. HNSW index with cosine distance |
| Keyword search | **PostgreSQL full-text search** | Already in the database, no extra service (no Elasticsearch) |
| Schema management | **Flyway** (`db/migration/V1 ... V7`) | Every database change is a numbered SQL file in git; the same schema on every machine |
| PDF reading | **Apache PDFBox** through the Spring AI PDF reader | Page by page, so every chunk knows its page number |
| DOCX reading | **Apache POI** | Reads headings and tables, so every chunk knows its section |
| Reliability | **Resilience4j** (retry, circuit breaker, bulkhead, rate limiter) + **Caffeine** cache | Small libraries with clear names, used from one class (`ProtectedCall`) |
| Metrics and traces | **Spring Boot Actuator, Micrometer, OpenTelemetry**, Prometheus, Jaeger | The Spring standard: Spring AI already reports model calls through it |
| Local infrastructure | **Docker Compose** | One command starts the database, the models and optional monitoring |
| Chat page | one static `index.html` (plain JavaScript, no build step) | The back end is the point of this project; the page has nothing to install |

## Why a vector database at all?

A normal database finds rows with the **same words**. A question like "How many vacation days do I get?" must find a paragraph that says
"annual leave entitlement". An *embedding model* turns text into a list of numbers so that **texts with similar meaning have similar numbers**.
A vector database stores these lists and quickly finds the closest ones to the question. That is called *similarity search*.

## Why pgvector and not a dedicated vector database?

Dedicated vector databases (Qdrant, Weaviate, Pinecone, Milvus) are excellent at very large scale. For HR documents (thousands to a few hundred
thousand chunks) PostgreSQL with pgvector is fast enough and has real advantages:

- documents, chunks, conversations and costs live in **one place** and can use one transaction;
- the tenant filter and the document status are ordinary SQL conditions;
- the same database can do the **keyword search** needed for hybrid search;
- the team already knows how to operate PostgreSQL.

If the data ever outgrows it, Spring AI's `VectorStore` abstraction limits the change to the storage class and its configuration.

## Why Spring AI?

- **Provider independence.** `ChatModel` and `EmbeddingModel` hide the provider. The same code runs on Ollama, OpenAI or Azure OpenAI. In this project
  only the Ollama starter is on the classpath; see [Extending and production](10-extending-and-production.md) for the switch.
- **Readers, splitter and vector store** for the ingestion pipeline: `PagePdfDocumentReader`, `TokenTextSplitter`, `PgVectorStore`.
- **Prompt templates** in files (`src/main/resources/prompts/*.st`), so a prompt can be changed and reviewed like code.
- **Observability** for model calls is built in (traces and token metrics).

**What the project does not use from Spring AI, and why.** The RAG *advisors* (`QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`) would hide the
steps of the pipeline. Here the pipeline is written out in plain classes (`RetrievalService`, `ContextBuilder`, `RagChatService`), because hybrid search, reranking,
masking, citation checking and the "not found" decision all need to sit **between** the steps, and because a junior developer can read and debug explicit code more easily.
A project that needs only plain vector search could use the advisor and be much shorter.

## Why these models?

| Model | Size | Role | Note |
|---|---|---|---|
| `llama3.2:3b` | about 2 GB | writes the answer | follows the "use only the context" rule well enough here, but sometimes writes wrong source numbers, so sources are chosen by the application, not the model (see [How it works](01-how-it-works.md)). Replace it with a bigger model if you have a GPU: change `CHAT_MODEL` in `.env` |
| `nomic-embed-text` | about 0.3 GB | text to vector | 768 dimensions. **If you change the embedding model, the vector size and the whole index change: upload the documents again** |

Settings are in `application.yml` (`spring.ai.ollama`): temperature 0.1 (factual, repeatable), context 8,192 tokens, at most 700 tokens per answer.

## Why chunks of 500 tokens?

- Too small (30-60 tokens): a chunk loses its context ("it is 10 days" - what is?).
- Too large (thousands of tokens): the vector describes many topics at once, search gets blurry, and the prompt becomes expensive and slow.
- About 500 tokens is roughly one or two paragraphs: one idea, enough context.

A PDF is cut page by page first, a DOCX at headings first, so a chunk never crosses a page or a section. That is what makes a precise citation possible.
The A/B test for chunk size is in [Evaluation](08-evaluation.md); the sample handbook pages are short, so it cannot show a difference. Test with your own documents.

## Why hybrid search and reranking?

Vector search is good at meaning and weak at exact things (form codes, names, numbers). Keyword search is the opposite. Using both, and then ordering the
combined results with a second look (rerank), gave the best and most stable results. Details and measurements: [Retrieval, privacy and safety](05-retrieval-privacy-and-safety.md).

## Why asynchronous ingestion?

A 500-page document needs minutes of embedding work. An HTTP request must not wait that long (timeouts, frozen screens). So the upload answers at once with
`202 Accepted`, a small thread pool processes the document in the background, and the status (`QUEUED`, `PARSING`, `EMBEDDING`, `COMPLETED`, `FAILED`) can be polled.

## Why no Spring Security?

It was explicitly out of scope. The tenant and user come from the headers `X-Tenant-Id` and `X-User-Id`, in **one** place (`CallerArgumentResolver`). All rules that depend on the
user (ownership of conversations, tenant filters in every search) already use that `Caller`. When real authentication is added, only that class changes.
**Until then, the headers can be forged: do not expose this application to a network you do not trust.**
