# Troubleshooting

Start with `curl http://localhost:8110/actuator/health` and `docker compose ps`. Most problems are visible there.

## The application does not start

| Symptom | Cause and fix |
|---|---|
| `Connection to localhost:5440 refused` or the application waits at the database | the database container is not running or not healthy yet: `docker compose up -d`, then `docker compose ps` until `rag-postgres` is `healthy` |
| `password authentication failed` | the password in `.env` does not match the one the database was created with. The database keeps the password of its first start. Either put the old password back, or delete the data (**this deletes all documents**): `docker compose down -v`, then `docker compose up -d` |
| the application ignores `.env` (wrong ports, default password) | start it **from the project folder**; the file is read from the current folder |
| `Port 8110 was already in use` | another instance is running. Stop it, or change `server.port` in `application.yml` |
| `Migration checksum mismatch` (Flyway) | a migration file that was already applied was edited. Never edit an applied `V*.sql`: add a new `V8__...sql`. In a throw-away development database you can reset with `docker compose down -v` |
| `Unsupported class file major version` or `invalid source release: 21` | wrong Java: `java -version` must say 21. Set `JAVA_HOME` |
| `docker compose` cannot pull images | no network or Docker Desktop is not running |

## Health shows `ollama: DOWN`, or answers fail

| Symptom | Cause and fix |
|---|---|
| `ollama` is `DOWN` | `docker compose ps`: is `rag-ollama` running? The first start downloads the models; watch `docker compose logs -f ollama-model-puller` |
| `model "llama3.2:3b" not found` | the download was interrupted. Run `docker compose up -d` again, or `docker exec rag-ollama ollama pull llama3.2:3b` |
| the first answer after a start takes 20 seconds or more | Ollama loads the model into memory on the first request. Later answers are faster (about 2 to 6 seconds on a CPU) |
| answers are very slow all the time | the 3B model on a CPU needs memory: close other programs, give Docker at least 8 GB, or use a smaller/faster machine. **When Docker has little free memory it becomes sluggish** |
| the chat page says "Limited answer" and shows passages instead of an answer | the model is unreachable or too slow and the circuit breaker opened. This is the intended fallback. Check Ollama, wait 30 seconds, ask again ([Reliability](06-reliability.md)) |
| `503 The system is busy` | more answers at the same time than the limit (4): wait and retry |
| `429 Too Many Requests` | a rate limit (20 questions per user per minute, 30 uploads per tenant per hour): wait for `Retry-After` seconds, or change `app.rate-limit` |

## A document does not work

| Symptom | Cause and fix |
|---|---|
| status stays `EMBEDDING` for minutes | normal for a large document. The 500-page handbook needs several minutes on a CPU. Watch `GET /api/documents/<id>` |
| `FAILED`: the document has more than 500 pages | the maximum is 500 pages (`app.documents.max-pages`). Split the file |
| `FAILED`: "No readable text was found" | a scanned PDF (only images). **OCR is not implemented.** Use a PDF with a text layer, or run OCR first |
| `FAILED`: the PDF could not be read | damaged or password-protected. Remove the password and upload again |
| `FAILED` after you stopped the application during processing | the next start marks interrupted documents `FAILED`. Upload the file again (this is allowed for `FAILED` documents) |
| `409 Conflict` | the same file (same content) is already uploaded for this tenant. Use the existing one, or delete it first |
| `400`: wrong file type | only PDF and DOCX; the content is checked, a renamed `.txt` is rejected |
| `413` | file above 100 MB |

## "I could not find this information" but the document has it

If the answer is instead **"No documents are available yet"**, nothing has been uploaded (or processed) for this tenant: upload a document and wait for `COMPLETED`.
A question of one vague word ("prompt") is refused on purpose, see [Retrieval, privacy and safety](05-retrieval-privacy-and-safety.md): ask a complete question.

Check in this order:

1. **Is the document `COMPLETED`?** (`GET /api/documents`). Only completed documents are searched.
2. **Same tenant?** The chat page and your curl call must send the same `X-Tenant-Id` as the upload (default: `default`).
3. **Is it really written in the document?** The text must be real text in the PDF (not an image), and the question should use words that are close to it.
4. **Look at the log** of that question: it prints how many chunks the search found and their scores
   (`Retrieval: vector=20 keyword=2 combined=21 ... scores=[0.80, 0.71, ...]`). If the best score is below `app.rag.similarity-threshold`, the vector part found nothing relevant.
5. **Is the question too vague?** "How many days?" has no topic. Ask the complete question, or ask it as a follow-up in a conversation.
6. **Is the answer being rejected?** The log says so, and the metric `rag_answer_rejected_total` rises, when the model's answer is not found in the documents.
7. Add the question to `eval-set.json` and run the evaluation after you fix it ([Evaluation](08-evaluation.md)).

## The answer is wrong or the source is wrong

- A 3B model makes mistakes. Compare the answer with the **source sentence** shown under it: that is the evidence.
- Use the thumbs-down button and "report as incorrect": the report is saved with the message, and is the best raw material for new regression questions.
- Try a bigger model (`CHAT_MODEL` in `.env`) and compare with the evaluation.
- Two documents that contradict each other (an old and a new version of a policy) confuse any system: delete the old one.

## Tests fail

| Symptom | Cause and fix |
|---|---|
| almost every test fails with a connection error | the Docker stack is not running or not healthy ([Setup](03-setup-run-test.md)) |
| tests that call the model time out | Ollama is slow or the model is not loaded; run one question by hand first to warm it up |
| `SampleDocsTest` says a document is missing | run `scripts\generate-sample-docs.cmd` |
| an evaluation test fails a quality gate | read `target/evaluation/answers.md` (what was wrong per question) **before** changing a number |
| leftover test data | tests clean up after themselves. If a run was killed, delete rows of tenants that start with `eval-` or `test-` from `documents` and `vector_store` |

## Changing the embedding model

If you change `EMBEDDING_MODEL`, the vectors have a different size and meaning. Update `dimensions` in `application.yml`, add a migration that recreates the `vector_store`
column and index with the new size, and upload all documents again. Searching old vectors with a new model gives meaningless results.
