# Extending the application and going to production

## Where the application is built to grow

| You want to ... | What to do | What does NOT change |
|---|---|---|
| add **more HR policies** | upload them (title and version are optional fields). New versions of a policy are new files; delete the old one with `DELETE /api/documents/{id}` | nothing in the code |
| add **another company or department** (tenant) | send another `X-Tenant-Id`: its documents and conversations are separate, in every search | nothing |
| support a **new file type** (HTML, Markdown, CSV, TXT) | 1. add the type to `DocumentType`, 2. write a class that implements `DocumentParser` and mark it `@Component` (see below) | chunking, embedding, search, chat |
| **change the chunk size** | `app.ingestion.chunk-size` in `application.yml`, then upload the documents again | search, chat |
| use a **bigger language model** | `CHAT_MODEL` in `.env` (for example `llama3.1:8b`), then `docker compose up -d` and restart the application | code |
| change **what is retrieved** | `app.rag.*` (top-k, threshold, hybrid and rerank switches), see [Retrieval](05-retrieval-privacy-and-safety.md) | code |
| change the **limits** | `app.rate-limit.*`, `app.resilience.*`, see [Reliability](06-reliability.md) | code |
| **add a question to the regression set** | one entry in `src/test/resources/evaluation/eval-set.json`, see [Evaluation](08-evaluation.md) | code |

### A new file type, in code

A parser returns the text in *sections* (a PDF page, a DOCX heading) with metadata. Everything after that is shared.

```java
@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public DocumentType supportedType() {
        return DocumentType.MARKDOWN;      // 1. add MARKDOWN to the enum DocumentType first
    }

    @Override
    public ParsedDocument parse(Path file) {
        // 2. read the file, return one Document per section: text + metadata ("section" = heading path)
        ...
        return new ParsedDocument(sections, null);   // null = the page count is unknown
    }
}
```

Also tell `DocumentValidator` how to recognise the type (it checks the content, not only the extension), and add a test with a small example file.
Look at `DocxDocumentParser` as the model: it is about 150 lines.

### Things that are **not** there yet, and where they would go

| Missing | Where it would be added |
|---|---|
| scanned PDFs (OCR) | a second `PdfDocumentParser` step: when a page has no text, run an OCR engine (for example Tesseract) over the page image. Today such a file ends `FAILED` with a clear message |
| per-document permissions (who may read which policy) | a `permissions` column on `documents` plus a filter next to the tenant filter in `ChunkSearchRepository`. Today access is **per tenant** only |
| tool or function calling (for example "how many days of leave do I have left?") | a `@Tool` method on a Spring bean, offered to the `ChatClient`. Needs real user identity first, see below |
| structured output | `chatClient.prompt()...call().entity(MyRecord.class)` for a part of the flow that needs data instead of text |
| real authentication | `CallerArgumentResolver` (the only class that reads `X-Tenant-Id` and `X-User-Id`): fill `Caller` from a verified token instead. Everything else already uses `Caller` |

## Switching the model provider (OpenAI, Azure OpenAI)

**This is not done and not tested in this project.** Spring AI is designed for it, and the code uses the provider-neutral
`ChatModel` / `EmbeddingModel` interfaces almost everywhere. These are the steps and the places that need attention:

1. In `pom.xml` replace `spring-ai-starter-model-ollama` by `spring-ai-starter-model-openai` (or the Azure starter). Remove `COMPOSE_PROFILES=ollama`, so that no local models are started.
2. In `application.yml` replace the `spring.ai.ollama` section by the provider's settings (API key from an **environment variable**, model names, temperature).
3. `config/AiConfig.java` uses `OllamaChatOptions` for the question-rewriting client: replace it by the generic options builder (`ChatOptions.builder()`) or the provider's options.
4. **Embedding size.** `text-embedding-3-small` has 1536 dimensions, not 768. Add a Flyway migration that recreates the column `vector_store.embedding` and the HNSW index with the new size, set
   `spring.ai.vectorstore.pgvector.dimensions` to the same number, and **upload all documents again**: vectors of different models cannot be compared.
5. Replace `health/OllamaHealthIndicator` by a check of the new provider (or remove it).
6. Enter the prices in `app.cost.*` (the example for gpt-4o-mini is in `application.yml`): the cost per question, day and tenant then appears.
7. **Privacy.** Documents and questions now leave the company. Keep `app.privacy.mask-pii: true`, check the contract of the provider (no training on your data, region), and read the limits of the masking in [Retrieval, privacy and safety](05-retrieval-privacy-and-safety.md): it finds common patterns, not names or addresses.
8. Run `mvnw test -Pevaluation` and compare the report with the numbers of the local model before releasing.

## Production checklist

The application is built with production habits (validation, limits, timeouts, retries, circuit breakers, migrations, metrics, alerts, tests), but it is **not production-ready until these are done**:

| Area | To do |
|---|---|
| **Authentication and authorisation** | Add real login (for example OAuth2 / OpenID Connect) and fill `Caller` from the token. Today anyone can send any `X-User-Id` or `X-Tenant-Id` |
| **Usage report** | `GET /api/usage` and `GET /actuator/**` are open: restrict them to administrators |
| **Secrets** | Use a secrets manager or the environment of the platform instead of `.env`; change `POSTGRES_PASSWORD`; never commit `.env` |
| **TLS** | Put the application behind HTTPS (reverse proxy, ingress). Set `app.cors.allowed-origins` to your real front-end address |
| **Packaging** | There is **no Dockerfile** for the application: it runs with `mvnw spring-boot:run` or `java -jar target/spring-rag-app-*.jar` (after `mvnw package`). A container image (multi-stage build with a Java 21 base) is a small addition if you deploy with containers |
| **Database** | Back it up (the volume `postgres-data`), size it for your data (HNSW indexes need memory), check `pg_stat_statements` for slow queries, think about a managed PostgreSQL with the pgvector extension |
| **Models** | A CPU-only 3B model is for demos. Use a GPU host or a managed model for real traffic and re-run the evaluation. Keep Ollama off the public network |
| **Several servers** | Rate limits and the embedding cache live in the memory of one server. For several instances move them to a shared store (Redis), and put uploaded files on shared storage (or object storage) instead of the local `uploads/` folder |
| **Monitoring** | Run Prometheus and Jaeger (or your platform's tools), lower `TRACING_SAMPLING` to 0.1 or less, add Alertmanager to **send** the alerts, tune the thresholds after a week of real traffic |
| **Logs** | Start with the profile `json` and ship the logs to your log system. Questions and answers are never logged, keep it that way |
| **Data protection** | Define the retention of documents and conversations (`app.conversations.retention-days`), check the retention table of your privacy policy, and decide who may export conversations |
| **Quality** | Grow `eval-set.json` with real questions, raise the quality gates when the system improves, run the evaluation in your pipeline before each release |
| **Backups of documents** | The original files are in `uploads/`. If you must re-embed after a model change, you need them |
| **Scanned documents** | Decide whether you need OCR (see above) |

## Project layout

```
docker-compose.yml        PostgreSQL + Ollama (+ Prometheus and Jaeger with --profile monitoring)
.env                      ports, passwords, model names (not in git)
monitoring/               prometheus.yml and alert-rules.yml
sample-docs/              the nine sample HRM documents (generated)
scripts/                  GenerateSampleDocs.java, generate-sample-docs.cmd/.sh, sample-content/*.txt (texts of the policies)
docs/                     this guide
src/main/resources/
  application.yml         application settings
  application-json.yml    JSON log lines with the profile "json"
  db/migration/           Flyway SQL: V1 pgvector, V2 documents, V3 vector_store, V4 conversations,
                          V5/V6 full-text search column, V7 usage_records
  prompts/                system prompt, user prompt, question rewrite prompt
  static/index.html       the chat page
src/main/java/com/example/ragapp/
  RagApplication.java     entry point
  common/                 exceptions, error handler, request id filter, caller (tenant and user)
  config/                 thread pools, ChatClient, streaming settings, CORS
  resilience/             ProtectedCall (timeout, retry, circuit breaker, limits), rate limits, settings
  document/               upload API, validation, file storage, document table
  ingestion/              background pipeline, chunk storage, startup recovery
  ingestion/parser/       PDF and DOCX readers (add a class here for a new file type)
  retrieval/              hybrid search (SQL searches, rank fusion), reranker, tenant filter
  privacy/                masking of personal data and secrets, restoring them in answers
  chat/                   chat API, RAG flow, context building, source selection, injection filter,
                          follow-up rewriting (QuestionRewriter), ChatService (connects everything)
  conversation/           saved conversations, feedback, export, delete, retention job
  observability/          RagMetrics: all our metrics and trace steps in one place
  usage/                  tokens, estimated cost, usage report per tenant
  evaluation/             GroundednessChecker (is the answer in the documents?)
  health/                 Ollama health check, circuit breaker state
src/test/resources/evaluation/
  corpus.json             the test handbook (10 pages)
  eval-set.json           the 41 regression questions
  judge-calibration.json  answers labelled by a person
  sample-questions.json   questions about the sample documents in sample-docs/
src/test/java/.../evaluation/   the evaluation tools: runner, report, quality gates, judge, A/B tests, sample document tests
```
