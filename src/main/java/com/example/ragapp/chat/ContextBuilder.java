package com.example.ragapp.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.retrieval.RagProperties;
import com.example.ragapp.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns retrieved chunks into the numbered text block that is sent to the language model:
 *
 *   [1] Leave Policy (page 3)
 *   ...text...
 *
 * On the way it cleans the text (removes order-like sentences, see PromptInjectionFilter),
 * removes duplicates and stops adding chunks when the size limit is reached.
 * The best matches come first, so they are the ones that survive the limit.
 */
@Component
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final RagProperties properties;
    private final PromptInjectionFilter injectionFilter;
    private final RagMetrics metrics;

    public ContextBuilder(RagProperties properties, PromptInjectionFilter injectionFilter, RagMetrics metrics) {
        this.properties = properties;
        this.injectionFilter = injectionFilter;
        this.metrics = metrics;
    }

    /**
     * The context text, plus the cleaned chunks that made it in
     * (the position in the list = the [n] number in the text, minus 1).
     */
    public record BuiltContext(String text, List<RetrievedChunk> chunks) {
    }

    public BuiltContext build(List<RetrievedChunk> retrieved) {
        StringBuilder text = new StringBuilder();
        List<RetrievedChunk> used = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();

        for (RetrievedChunk original : retrieved) {
            RetrievedChunk chunk = withCleanText(original);
            if (chunk.text().isBlank() || !seenTexts.add(chunk.text())) {
                continue;   // nothing left after cleaning, or identical text already included
            }
            String block = formatBlock(used.size() + 1, chunk);
            if (!used.isEmpty() && text.length() + block.length() > properties.maxContextChars()) {
                break;      // limit reached (the first chunk is always included)
            }
            text.append(block);
            used.add(chunk);
        }
        return new BuiltContext(text.toString(), used);
    }

    private RetrievedChunk withCleanText(RetrievedChunk chunk) {
        // Documents are untrusted: remove anything that looks like our own context tags ...
        String text = chunk.text().replace("<context>", "").replace("</context>", "");

        // ... and sentences that try to give the model orders
        PromptInjectionFilter.Result result = injectionFilter.clean(text);
        if (result.removedSentences() > 0) {
            log.warn("Removed {} suspicious sentence(s) from a chunk of document {} (possible prompt injection)",
                    result.removedSentences(), chunk.documentId());
            metrics.promptInjectionRemoved(result.removedSentences());
        }
        return new RetrievedChunk(chunk.documentId(), chunk.fileName(), chunk.title(), chunk.version(),
                chunk.pageNumber(), chunk.section(), tidySpaces(result.text()), chunk.score());
    }

    /**
     * Text read from a PDF often has irregular spacing ("+91  44 2345  6789", long runs of spaces).
     * One space between words is enough for the model, makes the prompt shorter, and lets the
     * personal data patterns (see MaskingSession) recognise phone numbers and similar values.
     */
    static String tidySpaces(String text) {
        return text.lines()
                .map(line -> line.replaceAll("[ \\t\\u00A0]+", " ").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private String formatBlock(int number, RetrievedChunk chunk) {
        return "[" + number + "] " + chunk.title() + " (" + location(chunk) + ")\n" + chunk.text() + "\n\n";
    }

    static String location(RetrievedChunk chunk) {
        if (chunk.pageNumber() != null) {
            return "page " + chunk.pageNumber();
        }
        if (chunk.section() != null) {
            return "section " + chunk.section();
        }
        return "document";
    }
}
