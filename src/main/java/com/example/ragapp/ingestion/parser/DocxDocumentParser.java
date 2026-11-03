package com.example.ragapp.ingestion.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.ragapp.document.DocumentProperties;
import com.example.ragapp.document.DocumentType;
import com.example.ragapp.ingestion.IngestionException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Reads a DOCX file. A Word file has no fixed pages (they depend on how Word draws it),
 * so we cut the text at headings instead. Each section remembers its heading path,
 * for example "Leave Policy > Annual Leave", which is used as the citation.
 * Tables are converted to text, one row per line: "cell | cell | cell".
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final int MAX_HEADING_LEVEL = 9;
    private static final String NO_HEADING = "Document start";

    private final DocumentProperties properties;

    public DocxDocumentParser(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public DocumentType supportedType() {
        return DocumentType.DOCX;
    }

    @Override
    public ParsedDocument parse(Path file) {
        try (InputStream in = Files.newInputStream(file); XWPFDocument docx = new XWPFDocument(in)) {

            Integer pageCount = readPageCount(docx);
            if (pageCount != null && pageCount > properties.maxPages()) {
                throw new IngestionException("The document has " + pageCount + " pages. The maximum is "
                        + properties.maxPages() + " pages.");
            }
            return new ParsedDocument(readSections(docx), pageCount);

        } catch (IOException | RuntimeException e) {
            if (e instanceof IngestionException ingestionException) {
                throw ingestionException;
            }
            throw new IngestionException("The DOCX file could not be read. It may be damaged or password protected.");
        }
    }

    private List<Document> readSections(XWPFDocument docx) {
        List<Document> sections = new ArrayList<>();
        String[] headings = new String[MAX_HEADING_LEVEL + 1];   // current heading for each level
        StringBuilder text = new StringBuilder();
        String sectionPath = NO_HEADING;

        for (IBodyElement element : docx.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                int level = headingLevel(paragraph);
                if (level > 0) {
                    addSection(sections, sectionPath, text);   // finish the previous section
                    text = new StringBuilder();
                    headings[level] = paragraph.getText().strip();
                    Arrays.fill(headings, level + 1, headings.length, null);   // deeper headings are reset
                    sectionPath = buildPath(headings);
                    text.append(headings[level]).append('\n');
                } else {
                    text.append(paragraph.getText()).append('\n');
                }
            } else if (element instanceof XWPFTable table) {
                text.append(tableToText(table));
            }
        }
        addSection(sections, sectionPath, text);
        return sections;
    }

    private void addSection(List<Document> sections, String sectionPath, StringBuilder text) {
        String cleaned = TextCleaner.clean(text.toString());
        if (!cleaned.isEmpty()) {
            sections.add(Document.builder()
                    .text(cleaned)
                    .metadata(Map.of("section", sectionPath))
                    .build());
        }
    }

    /** Returns 1 for "Heading 1", 2 for "Heading 2" ... and 0 for normal text. */
    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null || paragraph.getText().isBlank()) {
            return 0;
        }
        String lower = style.toLowerCase();
        if (lower.equals("title")) {
            return 1;
        }
        if (lower.startsWith("heading")) {
            String digits = lower.replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                return Math.min(Integer.parseInt(digits), MAX_HEADING_LEVEL);
            }
        }
        return 0;
    }

    private String buildPath(String[] headings) {
        String path = Arrays.stream(headings)
                .filter(h -> h != null && !h.isBlank())
                .collect(Collectors.joining(" > "));
        return path.isEmpty() ? NO_HEADING : path;
    }

    private String tableToText(XWPFTable table) {
        StringBuilder result = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            String line = row.getTableCells().stream()
                    .map(XWPFTableCell::getText)
                    .map(String::strip)
                    .collect(Collectors.joining(" | "));
            result.append(line).append('\n');
        }
        return result.toString();
    }

    /** Word stores the page count in the file properties when it saves the file (0 = unknown). */
    private Integer readPageCount(XWPFDocument docx) {
        int pages = docx.getProperties().getExtendedProperties().getPages();
        return pages > 0 ? pages : null;
    }
}
