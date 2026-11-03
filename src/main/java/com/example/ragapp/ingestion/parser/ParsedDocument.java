package com.example.ragapp.ingestion.parser;

import java.util.List;

import org.springframework.ai.document.Document;

/**
 * Result of reading a file.
 *
 * @param sections  pieces of text with metadata (page_number for PDF, section for DOCX)
 * @param pageCount number of pages, or null when the file type does not tell us
 */
public record ParsedDocument(List<Document> sections, Integer pageCount) {
}
