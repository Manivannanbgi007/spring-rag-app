package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.ragapp.document.DocumentProperties;
import com.example.ragapp.ingestion.parser.DocxDocumentParser;
import com.example.ragapp.ingestion.parser.ParsedDocument;
import com.example.ragapp.ingestion.parser.PdfDocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Checks the sample documents in sample-docs/ with the real parsers of the application (no database, no Ollama,
 * so it is fast). If you change a text in scripts/sample-content and run scripts/generate-sample-docs.cmd again,
 * this test shows whether the documents are still readable and still contain the key facts.
 */
class SampleDocsTest {

    private static final Path DIRECTORY = Path.of("sample-docs");
    private static final String HANDBOOK = "northwind-complete-employee-handbook-2026.pdf";

    private final DocumentProperties limits = new DocumentProperties("uploads", 500, 200);
    private final PdfDocumentParser pdfParser = new PdfDocumentParser(limits);
    private final DocxDocumentParser docxParser = new DocxDocumentParser(limits);

    /** A fact from every document. If the parser loses the text of a page or a section, one of these is missing. */
    private static final Map<String, List<String>> KEY_FACTS = Map.of(
            "01-leave-policy.pdf", List.of("25 working days", "31 March", "4 weeks of paternity leave"),
            "02-code-of-conduct-and-workplace-rules.docx", List.of("75 USD", "9:00 to 17:30"),
            "03-work-life-balance-guidelines.docx", List.of("19:00", "450 USD per month"),
            "04-employee-benefits-guide.pdf", List.of("1,000 USD", "5,000 USD"),
            "05-remote-and-hybrid-work-policy.pdf", List.of("20 working days", "HR-118"),
            "06-it-security-and-data-privacy-policy.docx", List.of("14 characters", "within 1 hour"),
            "07-health-safety-and-business-travel-policy.pdf", List.of("55 USD", "0.60 USD per kilometre"),
            "08-recruitment-performance-and-exit-policy.docx", List.of("90 days", "6 months"));

    @Test
    void everySampleDocumentExistsAndContainsItsKeyFacts() throws Exception {
        for (Map.Entry<String, List<String>> entry : KEY_FACTS.entrySet()) {
            Path file = DIRECTORY.resolve(entry.getKey());
            assertThat(file).as("missing sample document, run scripts/generate-sample-docs.cmd").exists();

            ParsedDocument parsed = parse(file);
            String text = parsed.sections().stream().map(Document::getText).collect(Collectors.joining("\n"));
            for (String fact : entry.getValue()) {
                assertThat(text).as(entry.getKey()).contains(fact);
            }
        }
    }

    @Test
    void pdfSectionsKnowTheirPageAndDocxSectionsTheirHeadings() {
        ParsedDocument pdf = parse(DIRECTORY.resolve("01-leave-policy.pdf"));
        assertThat(pdf.pageCount()).isBetween(2, 10);
        assertThat(pdf.sections()).allSatisfy(section -> assertThat(section.getMetadata()).containsKey("page_number"));

        ParsedDocument docx = parse(DIRECTORY.resolve("06-it-security-and-data-privacy-policy.docx"));
        assertThat(docx.sections().size()).as("one section per heading").isGreaterThan(5);
        assertThat(docx.sections()).allSatisfy(section -> assertThat(section.getMetadata()).containsKey("section"));
    }

    @Test
    void theHandbookHasExactly500PagesAndEveryPageHasText() {
        ParsedDocument handbook = parse(DIRECTORY.resolve(HANDBOOK));

        assertThat(handbook.pageCount()).isEqualTo(500);
        assertThat(handbook.sections()).as("no blank page").hasSize(500);
        assertThat(handbook.sections().stream().map(Document::getText).collect(Collectors.joining("\n")))
                .contains("Annex A - Offices", "Annex C - Catalogue of HR forms", "Form HR-250", "Course NA-")
                .contains("(555) 020-1000");
    }

    @Test
    void theHandbookContainsTheTextOfEveryPolicy() throws Exception {
        String handbook = parse(DIRECTORY.resolve(HANDBOOK)).sections().stream()
                .map(Document::getText).collect(Collectors.joining("\n"));
        // the facts of the policies are also in the handbook, so a question can be answered from either document
        KEY_FACTS.values().forEach(facts -> facts.forEach(fact -> assertThat(handbook).contains(fact)));
        assertThat(Files.size(DIRECTORY.resolve(HANDBOOK))).isLessThan(20L * 1024 * 1024);
    }

    private ParsedDocument parse(Path file) {
        return file.getFileName().toString().endsWith(".pdf") ? pdfParser.parse(file) : docxParser.parse(file);
    }
}
