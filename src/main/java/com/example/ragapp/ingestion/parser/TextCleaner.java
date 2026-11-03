package com.example.ragapp.ingestion.parser;

/** Small helper that cleans extracted text before it is stored. */
final class TextCleaner {

    private TextCleaner() {
    }

    /**
     * Removes NUL characters (PostgreSQL rejects them), repeated spaces and surrounding blank space.
     * PDF readers often pad the text with extra spaces to keep the layout ("Fee      270  USD"). Those spaces
     * only waste tokens and break searches for phrases, so they are collapsed. Line breaks are kept.
     */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\u0000", "")
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(" ?\\R ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
