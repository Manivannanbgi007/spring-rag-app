package com.example.ragapp.chat;

/**
 * One message of a streamed answer.
 *   type "question" -> data is a QuestionData (the follow-up question after rewriting; only sent when it changed)
 *   type "token"    -> data is a TokenData (a piece of the answer text)
 *   type "done"     -> data is the final ChatResponse (full answer, found flag, sources, ids)
 *   type "error"    -> data is an ErrorData
 */
public record ChatEvent(String type, Object data) {

    public record QuestionData(String text) {
    }

    public record TokenData(String text) {
    }

    public record ErrorData(String message) {
    }

    public static ChatEvent question(String standaloneQuestion) {
        return new ChatEvent("question", new QuestionData(standaloneQuestion));
    }

    public static ChatEvent token(String text) {
        return new ChatEvent("token", new TokenData(text));
    }

    /** Inside the RAG step the data is a RagAnswer; ChatService replaces it with a ChatResponse. */
    public static ChatEvent done(Object data) {
        return new ChatEvent("done", data);
    }

    public static ChatEvent error(String message) {
        return new ChatEvent("error", new ErrorData(message));
    }
}
