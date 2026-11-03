# API reference

All calls go to `http://localhost:8110`. Replace the example values. The headers `X-Tenant-Id` and `X-User-Id` are optional (see "Who is the user" below).

## Upload a document

```
curl -F "file=@leave-policy.pdf" -F "title=Leave Policy" -F "version=2025-01" http://localhost:8110/api/documents
```

The answer is `202 Accepted` with the document `id`. Processing continues in the background.
`title` and `version` are optional.

Follow the progress (repeat until `status` is `COMPLETED` or `FAILED`):

```
curl http://localhost:8110/api/documents/<id>
```

Other calls:

| Call | What it does |
|---|---|
| `GET /api/documents` | list all documents |
| `GET /api/documents/{id}` | status of one document |
| `DELETE /api/documents/{id}` | delete the document, its chunks and its file |

### Document statuses

`QUEUED` -> `PARSING` -> `EMBEDDING` -> `COMPLETED` (or `FAILED`, with the reason in `statusMessage`).

### Rules

- PDF and DOCX only. The file content is checked, not only the extension.
- Maximum 500 pages and 100 MB per file.
- Uploading the exact same file twice returns `409 Conflict`. A `FAILED` file can simply be uploaded again.
- Optional header `X-Tenant-Id` separates documents of different tenants (default: `default`).
- Scanned PDFs (images without text) are not supported yet and end as `FAILED` with a clear message.
- Time: a very dense 500-page PDF (about 1,500 chunks) took about 6 minutes on CPU-only Ollama.

## Ask a question

Complete answer in one JSON response:

```
curl -H "Content-Type: application/json" -d "{\"question\":\"How many days of annual leave do I get?\"}" http://localhost:8110/api/chat
```

```json
{
  "answer": "All full-time employees are entitled to 24 days of paid annual leave per calendar year.",
  "found": true,
  "sources": [
    { "number": 1, "fileName": "handbook.pdf", "title": "Leave Policy", "version": "2025-01",
      "pageNumber": 1, "section": null,
      "snippet": "All full-time employees are entitled to 24 days ...", "score": 0.76 }
  ]
}
```

Streamed answer (words arrive while the model writes; this is what the chat page uses):

```
curl -N -H "Content-Type: application/json" -d "{\"question\":\"How long is maternity leave?\"}" http://localhost:8110/api/chat/stream
```

The stream sends `token` events (pieces of text), then one `done` event with the final answer, `found` and `sources`.
If something fails you get an `error` event.

When the documents do not contain the answer, you get `"found": false`, the sentence
"I could not find this information in the available documents." and an empty `sources` list.

## Conversations, follow-ups and feedback

Every question belongs to a **conversation**. Leave `conversationId` out to start a new one; send the
`conversationId` from an earlier answer to continue it:

```
curl -H "Content-Type: application/json" -H "X-User-Id: maria" -d "{\"question\":\"How long is maternity leave?\"}" http://localhost:8110/api/chat
curl -H "Content-Type: application/json" -H "X-User-Id: maria" -d "{\"question\":\"What about paternity leave?\",\"conversationId\":\"<id from the first answer>\"}" http://localhost:8110/api/chat
```

The answer now also contains `conversationId`, `messageId` (needed for feedback) and `standaloneQuestion`
(how the question was understood).

| Call | What it does |
|---|---|
| `GET /api/conversations` | your conversations, newest first |
| `GET /api/conversations/{id}/messages` | all messages of one conversation, with sources and your feedback |
| `GET /api/conversations/{id}/export` | downloads the conversation as a JSON file |
| `DELETE /api/conversations/{id}` | deletes the conversation, its messages and its feedback |
| `PUT /api/messages/{messageId}/feedback` | rate an answer: `{"rating":"UP"\|"DOWN","comment":"...","reportedIncorrect":true}` (sending again replaces it) |

### Who is "the user"? (no login yet)

The user comes from the `X-User-Id` header (default `anonymous`) and the tenant from `X-Tenant-Id` (default `default`).
Every conversation lookup includes both, so one user cannot read, change or delete another user's conversation
(they get `404`, the same answer as for an id that does not exist).
**This is not real security**: anyone can send any header value. The chat page makes up a random user id once and keeps
it in the browser. When real authentication is added, only `CallerArgumentResolver` has to change.

### Memory versus RAG

- **Memory** (the saved messages) is used for one thing only: understanding follow-up questions.
- **RAG** (the documents) is the only source of facts. An earlier answer is never used as evidence.

This keeps answers grounded: if the model gave a wrong answer once, it cannot repeat it as a "fact" in the next one.

### How a follow-up question is handled

1. The last 6 messages of the conversation are loaded.
2. The language model rewrites the new question into a complete one ("What about paternity leave?" becomes "How long is paternity leave?"). The prompt (`prompts/rewrite-question.st`) contains three examples because small models need them.
3. The rewritten question is used to search the documents. The chat page shows it as "Understood as: ...", so the user can see (and correct) a misunderstanding.
4. The model output is checked: if it answered instead of rewriting, talks about "the conversation", or is empty or too long, the original question is used. If the model is down, the original question is used too.

Limit: the 3B model is not perfect. In testing it handled "Is it paid?" and "And how many can I carry over?" correctly, but resolved the ambiguous "and what do I get for it?" to the wrong topic. A bigger model would do better; that is a configuration change (see `CHAT_MODEL` in `.env`).

### Session timeout, retention and privacy

| Setting (`app.conversations`) | Default | Meaning |
|---|---|---|
| `session-timeout` | 8h | a conversation idle for longer cannot be continued (`410 Gone`); it can still be read and exported |
| `retention-days` | 30 | a nightly job (03:30) deletes conversations idle for longer, with messages and feedback |
| `history-messages` | 6 | recent messages used to understand a follow-up |

Question texts are never written to the log, and neither are feedback comments.

## Usage and health

| Call | What it does |
|---|---|
| `GET /api/usage?days=7` | questions, tokens, estimated cost and speed of the tenant, in total and per day (see [Monitoring](07-monitoring.md)) |
| `GET /actuator/health` | `db`, `ollama`, the state of every circuit breaker and the free call slots |
| `GET /actuator/prometheus` | all metrics in the Prometheus format |

## Errors

Every error is JSON in the standard "problem" format, with a clear text in `detail`:

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"This file was already uploaded as 'Leave Policy'."}
```

| Status | When |
|---|---|
| `400` | invalid input: empty question, question longer than 1000 characters, unreadable body, wrong file type, empty file, bad id |
| `404` | unknown document or conversation (also for the conversation of another user) |
| `409` | the same file was uploaded already |
| `410` | the conversation was idle longer than the session timeout |
| `413` | JSON larger than 64 KB, or file larger than 100 MB |
| `429` | rate limit reached (header `Retry-After`) |
| `503` | the system is busy, or the database or the model is unavailable and no limited answer is possible (header `Retry-After`) |
| `500` | an unexpected error: the user sees a short message, the details are in the log with the request id (header `X-Request-Id`) |
