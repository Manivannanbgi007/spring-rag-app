package com.example.ragapp.ingestion.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.ragapp.document.DocumentProperties;
import com.example.ragapp.document.DocumentType;
import com.example.ragapp.ingestion.IngestionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/** Reads a PDF page by page, so every piece of text knows its page number. */
@Component
public class PdfDocumentParser implements DocumentParser {

    private final DocumentProperties properties;

    public PdfDocumentParser(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public DocumentType supportedType() {
        return DocumentType.PDF;
    }

    @Override
    public ParsedDocument parse(Path file) {
        int pageCount = countPages(file);
        if (pageCount > properties.maxPages()) {
            throw new IngestionException("The document has " + pageCount + " pages. The maximum is "
                    + properties.maxPages() + " pages.");
        }

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)   // one Document per page
                .build();
        List<Document> pages = new PagePdfDocumentReader(new FileSystemResource(file), config).get();

        List<Document> sections = new ArrayList<>();
        for (Document page : pages) {
            String text = TextCleaner.clean(page.getText());
            if (!text.isEmpty()) {   // skip blank pages
                Object pageNumber = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
                sections.add(Document.builder()
                        .text(text)
                        .metadata(Map.of("page_number", pageNumber))
                        .build());
            }
        }
        return new ParsedDocument(sections, pageCount);
    }

    private int countPages(Path file) {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            return pdf.getNumberOfPages();
        } catch (IOException e) {
            throw new IngestionException("The PDF could not be read. It may be damaged or password protected.");
        }
    }
}
