package com.example.ragapp.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * "LLM as a judge": a language model decides whether an answer is supported by the evidence.
 *
 * It is useful because it understands different wording, which the word comparison of GroundednessChecker
 * does not. But a judge is a model too, and it can be wrong, especially a small one. So it is only ONE
 * signal, and it is measured against answers labelled by a person (JudgeCalibrationTest) before it is trusted.
 */
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    private final ChatClient chatClient;

    public LlmJudge(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** @return true = supported, false = not supported, null = the judge gave no clear verdict */
    public Boolean isSupported(String question, String evidence, String answer) {
        String prompt = """
                You check answers against a text. Say SUPPORTED when the text says the same as the answer.
                Say UNSUPPORTED when the answer has a different number, a different name, a different month, or a fact that is not in the text.

                Example 1
                TEXT: The shop opens at 9:00 and closes at 18:00.
                ANSWER: The shop opens at 9:00.
                VERDICT: SUPPORTED

                Example 2
                TEXT: The shop opens at 9:00 and closes at 18:00.
                ANSWER: The shop opens at 8:00.
                VERDICT: UNSUPPORTED

                Example 3
                TEXT: Staff get 20 days of leave.
                ANSWER: Staff get 20 days of leave and a free lunch.
                VERDICT: UNSUPPORTED

                Now the real case.
                TEXT: %s
                QUESTION: %s
                ANSWER: %s
                VERDICT (one word, SUPPORTED or UNSUPPORTED):""".formatted(evidence, question, answer);
        try {
            String verdict = chatClient.prompt().user(prompt).call().content();
            return parse(verdict);
        } catch (RuntimeException e) {
            log.warn("The judge could not be asked: {}", e.getMessage());
            return null;
        }
    }

    /** "UNSUPPORTED" contains the word "SUPPORTED", so it must be checked first. */
    static Boolean parse(String verdict) {
        if (verdict == null) {
            return null;
        }
        String upper = verdict.toUpperCase();
        if (upper.contains("UNSUPPORTED") || upper.contains("NOT SUPPORTED")) {
            return false;
        }
        if (upper.contains("SUPPORTED")) {
            return true;
        }
        return null;
    }
}
