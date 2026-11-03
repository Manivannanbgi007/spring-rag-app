package com.example.ragapp.ingestion.parser;

import java.nio.file.Path;

import com.example.ragapp.document.DocumentType;

/**
 * Reads a file and returns its text in sections (one section per PDF page,
 * or one per DOCX heading). To support a new file type, write a new class that
 * implements this interface and mark it with @Component.
 */
public interface DocumentParser {

    DocumentType supportedType();

    ParsedDocument parse(Path file);
}
