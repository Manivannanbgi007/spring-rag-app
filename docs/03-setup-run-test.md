# Setup, run and test (step by step)

All commands are for the Windows command prompt (`cmd`) and run **from the project folder** `spring-rag-app`. On Linux or macOS use `./mvnw` instead of `mvnw`
and `cp` instead of `copy`.

## 1. What you need

| Tool | Version | Check |
|---|---|---|
| Java (JDK) | 21 | `java -version` |
| Docker Desktop | running | `docker version` |
| curl (comes with Windows 10/11) | any | `curl --version` |
| Free disk space | about 6 GB | models about 2.5 GB, Docker images about 2 GB |
| Free memory | 8 GB or more | the 3B model needs about 4 GB |

You do **not** need to install Maven (the wrapper `mvnw` downloads it), PostgreSQL or Ollama (they run in Docker).

## 2. Configure

```
copy .env.example .env
```

Open `.env` and set `POSTGRES_PASSWORD` to a password of your choice. Everything else has a working default:

| Setting | Default | Meaning |
|---|---|---|
| `POSTGRES_PASSWORD` | `change-me` | database password (**change it**) |
| `POSTGRES_PORT` | 5440 | database port on your computer (not 5432, so that it does not clash with another database) |
| `OLLAMA_PORT` | 11440 | Ollama port on your computer |
| `CHAT_MODEL` | `llama3.2:3b` | the language model |
| `EMBEDDING_MODEL` | `nomic-embed-text` | the embedding model |
| `COMPOSE_PROFILES` | `ollama` | start the local models (remove the word if you use a cloud model) |

`.env` is ignored by git, so the password is never committed. The application reads the same file, so run it from this folder.

## 3. Start the infrastructure

```
docker compose up -d
docker compose ps
```

The first start downloads the images and about 2.5 GB of models (`rag-ollama-model-puller` shows progress: `docker compose logs -f ollama-model-puller`).
Wait until `rag-postgres` and `rag-ollama` show `healthy`.

## 4. Start the application

```
mvnw spring-boot:run
```

On the first start Flyway creates the database schema (you see `Migrating schema ... to version 7`). When you see `Started RagApplication`, check the health:

```
curl http://localhost:8110/actuator/health
```

`db` and `ollama` must both be `UP`. Then open **http://localhost:8110/** : the chat page.

## 5. Try it with the sample documents

The folder `sample-docs/` already contains nine documents (see [Sample documents](09-sample-documents.md)). With the chat page: click the upload field, choose a file,
wait until it shows `COMPLETED`, then ask a question. With curl:

```
curl -F "file=@sample-docs/01-leave-policy.pdf" -F "title=Leave Policy" -F "version=2026-01" http://localhost:8110/api/documents
curl http://localhost:8110/api/documents
curl -H "Content-Type: application/json" -d "{\"question\":\"How many days of sick leave on full pay do I get per year?\"}" http://localhost:8110/api/chat
```

Expected: an answer that mentions 12 days, with the source `01-leave-policy.pdf`, a page number and the sentence it is based on.
Ask something the documents do not contain ("Does Northwind offer a company car?") and you get "I could not find this information in the available documents."

The 500-page handbook takes several minutes to process, because every chunk is embedded by a model that runs on your CPU. The upload answers at once; watch the status:

```
curl http://localhost:8110/api/documents/<id>
```

## 6. Run the tests

The tests use the real database and the real embedding model, so the Docker stack of step 3 must be running.

| Command | What it runs | Time |
|---|---|---|
| `mvnw test` | about 200 tests: unit tests, the whole application against the real database, a pretend Ollama that fails on purpose, the retrieval evaluation, the A/B comparisons of the search, and the check of the sample documents | about 3 minutes |
| `mvnw test -Dtest=SampleDocsTest` | only the check of the sample documents (no database needed) | seconds |
| `mvnw test -Pevaluation -Dtest=AnswerEvaluationTest` | 41 questions through the whole system with the real language model | about 3 minutes |
| `mvnw test -Pevaluation -Dtest=SampleDocsEvaluationTest` | uploads all sample documents (including the 500 pages) and asks the sample questions | see [Sample documents](09-sample-documents.md) |

Tests use their own tenants and delete their data afterwards. The meaning of the evaluation numbers is in [Evaluation](08-evaluation.md).

## 7. Optional: monitoring

```
docker compose --profile monitoring up -d
set TRACING_EXPORT=true
mvnw spring-boot:run
```

Prometheus: http://localhost:9190, Jaeger: http://localhost:16690. See [Monitoring](07-monitoring.md).

## 8. Stop

Stop the application with `Ctrl+C`. Stop the infrastructure with:

```
docker compose down
```

Add `-v` to also delete the database and the downloaded models (the next start downloads them again).

## Ports at a glance

| Port | What |
|---|---|
| 8110 | the application (chat page, API, `/actuator`) |
| 5440 | PostgreSQL + pgvector |
| 11440 | Ollama |
| 9190 | Prometheus (optional) |
| 16690 | Jaeger page (optional) |
| 14318 | where the application sends traces (optional) |

All Docker ports are bound to `127.0.0.1`: they are not reachable from other computers.

## Using JSON logs

```
mvnw spring-boot:run -Dspring-boot.run.profiles=json
```

One JSON object per log line, with request id, trace id, tenant and the numbers of each question. See [Monitoring](07-monitoring.md).
