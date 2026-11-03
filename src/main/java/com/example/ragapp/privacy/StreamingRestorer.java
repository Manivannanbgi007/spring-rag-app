package com.example.ragapp.privacy;

/**
 * Restores placeholders in an answer that arrives in small pieces ("tokens").
 *
 * A token can end in the middle of a placeholder: "[EMA" then "IL_1] is ...". Restoring each
 * piece alone would miss it. So when the text ends with an unfinished "[...", that part is
 * held back until the next piece arrives. When nothing was masked, text passes straight through.
 */
public class StreamingRestorer {

    private static final int LONGEST_PLACEHOLDER = 14;   // for example "[SECRET_123]"

    private final MaskingSession session;
    private final StringBuilder held = new StringBuilder();

    StreamingRestorer(MaskingSession session) {
        this.session = session;
    }

    /** Takes the next piece of the answer and returns the text that is safe to show now. */
    public String feed(String piece) {
        if (!session.hasMaskedValues()) {
            return piece;
        }
        held.append(piece);
        int open = held.lastIndexOf("[");
        boolean unfinishedPlaceholder = open >= 0
                && held.indexOf("]", open) < 0
                && held.length() - open <= LONGEST_PLACEHOLDER;

        if (!unfinishedPlaceholder) {
            return flush();
        }
        String safe = held.substring(0, open);
        held.delete(0, open);
        return session.restore(safe);
    }

    /** Returns whatever is still held back. Call it when the answer has ended. */
    public String flush() {
        String rest = session.restore(held.toString());
        held.setLength(0);
        return rest;
    }
}
