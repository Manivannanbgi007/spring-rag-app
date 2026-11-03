# Sample documents

The folder `sample-docs/` contains nine documents of a **fictional company, Northwind Ltd**. All names, places, telephone numbers (`555`) and e-mail addresses (`.example`) are invented.
They are written so that a question has a clear, checkable answer, and so that the documents agree with each other (a number in the Benefits Guide is the same in the Handbook).

## The documents

| File | Format | Pages | Content |
|---|---|---|---|
| `01-leave-policy.pdf` | PDF | 4 | annual leave by grade (25 / 27 / 30 days), carry-over, sick leave, maternity, paternity, adoption, parental and carers' leave, special leave, unpaid leave, sabbatical |
| `02-code-of-conduct-and-workplace-rules.docx` | DOCX | - | values, working hours, respect and equal opportunity, dress code, gifts and conflicts of interest, anti-bribery, confidentiality, parking, disciplinary and grievance procedures |
| `03-work-life-balance-guidelines.docx` | DOCX | - | flexible hours, right to disconnect, meeting-free time, on-call, wellbeing day, family support, career breaks |
| `04-employee-benefits-guide.pdf` | PDF | 4 | health and dental insurance, pension match, share purchase plan, learning budget, allowances, recognition, relocation, benefits by grade |
| `05-remote-and-hybrid-work-policy.pdf` | PDF | 3 | hybrid model (3 remote days, team days), working from another country, equipment, expenses and tax, security when remote |
| `06-it-security-and-data-privacy-policy.docx` | DOCX | - | passwords and MFA, devices, information classification, safe behaviour, AI tools, incident reporting, data protection rights, retention periods, monitoring |
| `07-health-safety-and-business-travel-policy.pdf` | PDF | 3 | emergencies, first aid, ergonomics, flights, hotels, per diem, mileage, expense claims and approval limits, company cards |
| `08-recruitment-performance-and-exit-policy.docx` | DOCX | - | recruitment, probation, reviews and ratings, promotion, improvement plans, notice periods, exit, redundancy, retirement |
| **`northwind-complete-employee-handbook-2026.pdf`** | PDF | **500** | the eight policies as chapters, plus annexes **A** offices and public holidays (40 offices), **B** entitlements for each of the 12 grades, **C** catalogue of 150 HR forms, **D** rules of 36 departments, **E** frequently asked questions, **F** glossary, **G** course datasheets |

Four policies are PDF and four are DOCX, so both parsers are exercised (a PDF is cited with its **page**, a DOCX with its **section**).
The handbook is exactly **500 pages**, the largest size the application accepts. Its policy chapters are real text; the annexes are generated from tables, so their
facts are invented but consistent. They exist to give the search a large, fact-rich document, with many near-identical pages (forms, offices, courses),
which is the hard case for retrieval.

## How big, how long (measured on this project's development computer, CPU-only Ollama)

| Document | Chunks | Processing time |
|---|---|---|
| each of the eight policies | 5 to 24 | 1 to 3 seconds |
| the 500-page handbook | 542 | **about 108 seconds** |

Your time depends on your computer: the time is spent in the embedding model, and a denser 500-page PDF has more chunks (a very dense one with about 1,500 chunks took about 6 minutes).
The upload itself answers immediately and the status shows the progress ([How it works](01-how-it-works.md)).

## Try it

Upload the documents (all at once with the chat page, or one by one with curl), wait until each is `COMPLETED`, and ask:

| Question | Expected answer | Source |
|---|---|---|
| How many days of sick leave on full pay do I get per year? | 12 days | Leave Policy |
| How long is paternity leave? | 4 weeks | Leave Policy |
| When do carried-over leave days expire? | 31 March | Leave Policy |
| From which value must I declare a gift? | 75 USD | Code of Conduct |
| From what time do I not have to answer messages any more? | 19:00 | Work-Life Balance |
| How much is the childcare allowance per month? | 150 USD per child (maximum 450) | Work-Life Balance, Benefits |
| How high is the employee referral bonus? | 1,000 USD | Benefits, Recruitment |
| How many days per week may I work remotely? | up to 3 | Remote Work |
| How long must a password be? | 14 characters | IT Security |
| Within how much time must I report a security incident? | 1 hour | IT Security |
| What is the maximum hotel price per night in a tier 2 city? | 200 USD | Travel |
| What is the notice period for an employee of grade G10 after probation? | 6 months | Recruitment |
| What is form HR-160 used for? | the company credit card | Travel, Handbook annex C |
| What is the phone number of the reception desk of the Aldermoor office? | +1 (555) 020-1000 | Handbook annex A |
| What are the opening hours of the Brightwater office? | 8:00 to 19:00 | Handbook annex A |
| What is the fee of the course NA-110? | 600 USD | Handbook annex G |
| Does Northwind offer a company car to its employees? | **not found** | - |
| How much is the salary of the chief executive? | **not found** | - |
| Can I be paid my salary in cryptocurrency? | **not found** | - |

Because the handbook contains every policy, a question about leave can be answered from the Leave Policy **and** from the Handbook: both appear as sources.
Follow-ups to try (in one conversation): *"How long is maternity leave?"* then *"What about paternity leave?"*; *"What is the hotel limit in a tier 2 city?"* then *"And in a tier 1 city?"*.

### The same questions as an automated test

The 24 questions are in `src/test/resources/evaluation/sample-questions.json` (an answer pattern for each). Two tests use the documents:

| Test | What it checks | Time |
|---|---|---|
| `SampleDocsTest` (part of `mvnw test`, no database needed) | every file exists and is readable by the real parsers, key facts are in the extracted text, the handbook has exactly 500 pages with text on every page | seconds |
| `SampleDocsEvaluationTest` (`mvnw test -Pevaluation -Dtest=SampleDocsEvaluationTest`) | uploads all nine documents, then checks that the search finds the right passage for every question, and that the written answers contain the expected facts, cite the right document, and that unanswerable questions are refused | about 10 minutes (CPU) |

Results of the last run are in the section "Results" at the end of this chapter.

## Change or regenerate the documents

The policy texts are plain text files in `scripts/sample-content/`. Edit a text, then run:

```
scripts\generate-sample-docs.cmd
```

(on Linux or macOS: `sh scripts/generate-sample-docs.sh`). It rebuilds all nine documents in `sample-docs/` using the libraries of the project (PDFBox and Apache POI), nothing else to install.
Markup of the text files: `# ` document title, `## 1. Section`, `### 1.1 Subsection`, `- ` list item, lines starting with `|` form a table (first row = header), a blank line starts a new paragraph.

The generator is one readable file: `scripts/GenerateSampleDocs.java`. The annexes of the handbook are produced there from tables (grades, offices, forms, departments, courses), and annex G is
padded with course datasheets until the document has exactly 500 pages. If you edit a fact that appears in several places (for example the number of leave days), search for it in
`scripts/sample-content/` **and** in the annex code, then run `mvnw test -Dtest=SampleDocsTest` and the sample questions again.

## Use your own documents

Any PDF with a text layer, or any DOCX, up to 500 pages and 100 MB, can be uploaded. For a first test of a real policy, write 10 questions whose answers you know,
add them as a small file like `sample-questions.json`, and run the evaluation: it will show quickly whether the search and the model work for **your** wording and layout.
Scanned PDFs are not supported ([Troubleshooting](11-troubleshooting.md)).

## Results

Last run of `SampleDocsEvaluationTest` (llama3.2:3b and nomic-embed-text on a CPU, about 10 minutes in total, of which 108 seconds were the 500-page handbook):

| Check | Result |
|---|---|
| search finds the passage with the answer (top 5 chunks) | **21 of 21** |
| answers: correct, cited from the right document, refused when unknown | **23 of 24 questions without a problem** (all 3 unanswerable questions were refused) |

The one problem is worth reading, because it is typical. *"How many days per week may I work remotely?"* was answered correctly (up to 3), but the **source shown was the Benefits Guide**
(which mentions "working remotely at least 2 days a week" for the home office allowance) instead of the Remote Work Policy.
The sources are chosen by comparing the words of the answer with the retrieved chunks ([How it works](01-how-it-works.md)), and the Benefits chunk shared enough words.
The search had found the right passage, so this is a weakness of the source selection, not of the retrieval. The test tolerates up to two such questions
(a tenth of the set, like the quality gates of the main evaluation); if it happens in your use, the thumbs-down button and `eval-set.json` are the way to catch it.
