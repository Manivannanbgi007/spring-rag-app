# HRM Policy Chatbot (Spring AI + RAG + pgvector)

Upload HR policy documents (PDF or DOCX) and ask questions about them in a chat. Answers come **only** from the uploaded documents, show **where** they
come from (document, page or section, and the sentence), and say "I could not find this information" when the documents do not contain the answer.

Built with Spring Boot 4.1, Spring AI 2.0, PostgreSQL + pgvector and local models (Ollama). The application runs from the command prompt;
Docker Compose runs everything it needs. Written to be read by a junior developer.

> **Status: complete.** Infrastructure, upload and background processing of documents up to 500 pages, chat with sources and streaming,
> conversations with follow-up questions, hybrid search with reranking, personal data masking, reliability, monitoring, quality evaluation, and nine sample documents.
> What is **not** implemented (for example tool calling, OCR, real authentication) is listed openly in [Requirements coverage](docs/12-requirements-coverage.md).

## Quick start

You need Java 21 and Docker Desktop (running). In a command prompt, in this folder:

```
copy .env.example .env
```

Open `.env` and set `POSTGRES_PASSWORD`. Then:

```
docker compose up -d
docker compose ps
```

Wait until `rag-postgres` and `rag-ollama` are `healthy` (the first start downloads about 2.5 GB of models). Then:

```
mvnw spring-boot:run
```

Open **http://localhost:8110/**, upload a file from `sample-docs/` (wait for `COMPLETED`), and ask: *"How many days of sick leave on full pay do I get per year?"*

The full instructions, with checks after every step, are in [Setup, run and test](docs/03-setup-run-test.md).

## The guide

Read the chapters in this order, or jump to what you need.

| Chapter | What you learn |
|---|---|
| [1. How it works](docs/01-how-it-works.md) | the whole workflow, step by step, from upload to answer |
| [2. Technology choices](docs/02-technology-choices.md) | what is used and **why** (Spring AI, pgvector, Ollama, chunking, hybrid search ...) |
| [3. Setup, run and test](docs/03-setup-run-test.md) | commands for setup, configuration, start, tests, stop; ports |
| [4. API reference](docs/04-api-reference.md) | upload, chat, streaming, conversations, feedback, usage, errors |
| [5. Retrieval, privacy and safety](docs/05-retrieval-privacy-and-safety.md) | hybrid search, reranking, masking of personal data, prompt injection |
| [6. Reliability](docs/06-reliability.md) | timeouts, retries, circuit breakers, rate limits, graceful degradation |
| [7. Monitoring](docs/07-monitoring.md) | metrics, traces, JSON logs, cost, alerts |
| [8. Evaluation](docs/08-evaluation.md) | how answer quality is measured and kept from getting worse |
| [9. Sample documents](docs/09-sample-documents.md) | the nine HRM documents, the 500-page handbook, sample questions |
| [10. Extending and production](docs/10-extending-and-production.md) | new file types, other models, project layout, production checklist |
| [11. Troubleshooting](docs/11-troubleshooting.md) | symptoms and fixes |
| [12. Requirements coverage](docs/12-requirements-coverage.md) | every requirement: done, partly done, not done |

## What is in the folder

```
docker-compose.yml   PostgreSQL + pgvector, Ollama (+ Prometheus and Jaeger on request)
.env.example         settings: copy to .env
sample-docs/         nine sample HRM documents (8 policies, one 500-page handbook)
scripts/             generator of the sample documents and the texts it uses
docs/                the guide
monitoring/          Prometheus configuration and alert rules
src/                 the application, its tests and the evaluation data
```

## Sample documents

`sample-docs/` is ready to use. It describes a fictional company, **Northwind Ltd**, and contains leave, code of conduct, work-life balance, benefits,
remote work, IT security and data privacy, health, safety and travel, and recruitment, performance and exit policies, as four PDF and four DOCX files,
plus `northwind-complete-employee-handbook-2026.pdf`, a PDF of exactly 500 pages (the largest size the application accepts) that contains all policies and large annexes.
Everything in them is invented. See [Sample documents](docs/09-sample-documents.md) for the questions to try and how to regenerate or change them.

## The main commands

| What | Command |
|---|---|
| start the infrastructure | `docker compose up -d` |
| start the application | `mvnw spring-boot:run` |
| run the tests (about 3 minutes, needs Docker) | `mvnw test` |
| run the quality evaluation with the real model | `mvnw test -Pevaluation -Dtest=AnswerEvaluationTest` |
| regenerate the sample documents | `scripts\generate-sample-docs.cmd` |
| start monitoring (optional) | `docker compose --profile monitoring up -d` |
| stop everything | `docker compose down` |
