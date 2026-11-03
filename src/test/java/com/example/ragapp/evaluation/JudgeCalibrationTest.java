package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Can the judge model be trusted? Before the judge is allowed to say anything about quality, it is
 * checked against answers that a PERSON labelled (judge-calibration.json): 8 correct answers and
 * 8 answers with a small mistake. Two checkers are compared with the human:
 *   - the judge model                       (understands wording, but it is a small model)
 *   - the word check (GroundednessChecker)  (no model: strict about numbers, blind to meaning)
 *
 * Run with:   mvnw test -Pevaluation -Dtest=JudgeCalibrationTest
 */
@Tag("evaluation")
@SpringBootTest
class JudgeCalibrationTest {

    /** The judge must beat a coin flip by a clear margin, or its verdicts are ignored. */
    private static final double MIN_AGREEMENT_OF_JUDGE = 0.70;
    private static final double MIN_AGREEMENT_OF_WORD_CHECK = 0.85;

    record Example(int page, String question, String answer, String human, String note) {
    }

    record Examples(String description, List<Example> examples) {
    }

    @Autowired @Qualifier("rewriteChatClient") private ChatClient judgeClient;
    @Autowired private GroundednessChecker groundednessChecker;
    @Value("${spring.ai.ollama.chat.options.model}") private String model;

    @Test
    void theJudgeAndTheWordCheckAgreeWithTheHumanLabels() throws IOException {
        EvalCorpus corpus = EvalCorpus.load();
        List<Example> examples = load();
        LlmJudge judge = new LlmJudge(judgeClient);

        int judgeRight = 0;
        int judgeUnclear = 0;
        int wordCheckRight = 0;
        List<String> rows = new ArrayList<>();
        for (Example example : examples) {
            boolean human = example.human().equals("SUPPORTED");
            String evidence = corpus.pageText(example.page());

            Boolean verdict = judge.isSupported(example.question(), evidence, example.answer());
            boolean wordCheck = groundednessChecker.check(example.answer(), List.of(evidence)).score() >= 0.99;

            judgeRight += verdict != null && verdict == human ? 1 : 0;
            judgeUnclear += verdict == null ? 1 : 0;
            wordCheckRight += wordCheck == human ? 1 : 0;
            rows.add(String.format("| %s | %s | %s | %s | %s |", example.answer().replace("|", "/"),
                    example.human().toLowerCase(), verdict == null ? "unclear" : verdict ? "supported" : "unsupported",
                    wordCheck ? "supported" : "unsupported", example.note() == null ? "" : example.note()));
        }

        double judgeAgreement = (double) judgeRight / examples.size();
        double wordCheckAgreement = (double) wordCheckRight / examples.size();
        System.out.printf("%nJUDGE CALIBRATION (%d human-labelled answers, model %s): judge agrees with the human on %.0f%% "
                        + "(%d unclear), word check on %.0f%%%n", examples.size(), model, judgeAgreement * 100, judgeUnclear,
                wordCheckAgreement * 100);

        boolean judgeTrusted = judgeAgreement >= MIN_AGREEMENT_OF_JUDGE;
        System.out.println("JUDGE VERDICT: " + (judgeTrusted ? "can be trusted as a second opinion"
                : "NOT trusted (below " + (int) (MIN_AGREEMENT_OF_JUDGE * 100) + "%), its verdicts must be ignored"));

        Path file = Path.of("target", "evaluation", "judge-calibration.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# Judge calibration\n\nModel: " + model + "\n\n"
                + String.format("Agreement with the human labels: **judge %.0f%%** (unclear verdicts: %d), **word check %.0f%%**\n\n",
                judgeAgreement * 100, judgeUnclear, wordCheckAgreement * 100)
                + "Judge " + (judgeTrusted ? "**can be trusted** as a second opinion." : "**is NOT trusted**: its verdicts are ignored.")
                + " (Limit: " + (int) (MIN_AGREEMENT_OF_JUDGE * 100) + "%.)\n\n"
                + "| Answer | Human | Judge | Word check | Mistake |\n|---|---|---|---|---|\n" + String.join("\n", rows) + "\n");

        // The word check is the measure that the quality gates use, so IT must be good. A weak judge is not a
        // failure of the build: it only means that the judge is not used (the report says so).
        assertThat(wordCheckAgreement).as("the word check agrees with the human (see " + file + ")")
                .isGreaterThanOrEqualTo(MIN_AGREEMENT_OF_WORD_CHECK);
    }

    private List<Example> load() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/evaluation/judge-calibration.json")) {
            return JsonMapper.builder().build().readValue(in, Examples.class).examples();
        }
    }
}
