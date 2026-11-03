package com.example.ragapp.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

/**
 * No Spring, no model: checks that sources are chosen by the words in the answer.
 * The texts below are the pages of a sample handbook. The first test is the case where
 * the language model wrote a wrong source number ([3]) for the sick leave answer.
 */
class AnswerAttributionTest {

    private final AnswerAttribution attribution = new AnswerAttribution();

    private final List<RetrievedChunk> chunks = List.of(
            chunk(2, "3. Sick Leave\nEmployees receive 10 days of paid sick leave per year.\n"
                    + "A medical certificate is required when sick leave lasts more than 3 consecutive days.\n"
                    + "4. Maternity and Paternity Leave\nMaternity leave is 26 weeks on full pay."),
            chunk(1, "1. Annual Leave\nAll full-time employees are entitled to 24 days of paid annual leave per calendar year.\n"
                    + "Up to 5 unused days may be carried forward to the next year."),
            chunk(4, "7. Health Insurance\nThe company pays 100 percent of the health insurance premium for the employee.\n"
                    + "8. Learning Budget\nEach employee has an annual learning budget of 1,000 USD."),
            chunk(3, "5. Remote Work\nEmployees may work remotely up to 2 days per week with manager approval."));

    @Test
    void picksTheChunkThatContainsTheAnswerWords() {
        String answer = "A medical certificate is required when sick leave lasts more than 3 consecutive days.";

        assertThat(attribution.selectSupportingChunks(answer, chunks)).containsExactly(0);
    }

    @Test
    void picksTheRightChunkEvenWhenItIsNotFirst() {
        String answer = "Employees may work remotely up to 2 days per week with manager approval.";

        assertThat(attribution.selectSupportingChunks(answer, chunks)).containsExactly(3);
    }

    @Test
    void answerUsingTwoChunksGetsBothSources() {
        String answer = "Employees may work remotely up to 2 days per week. "
                + "The company pays 100 percent of the health insurance premium.";

        assertThat(attribution.selectSupportingChunks(answer, chunks)).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void wordsFoundInEveryChunkDoNotCreateExtraSources() {
        // every page repeats the handbook title, and the model mentions it in its answer
        List<RetrievedChunk> pages = List.of(
                chunk(1, "ACME Corp Employee Handbook. Annual leave is 24 days per calendar year."),
                chunk(2, "ACME Corp Employee Handbook. Sick leave is 10 days per year."),
                chunk(3, "ACME Corp Employee Handbook. Employees may work remotely up to 2 days per week."),
                chunk(4, "ACME Corp Employee Handbook. Health insurance is paid by the company."));
        String answer = "According to the ACME Corp Employee Handbook, employees may work remotely up to 2 days per week.";

        assertThat(attribution.selectSupportingChunks(answer, pages)).containsExactly(2);
    }

    @Test
    void secondChunkWithTheSameWordsIsNotAnExtraSource() {
        // a very short answer: both pages contain all of its words, so the second adds nothing
        List<RetrievedChunk> pages = List.of(
                chunk(3, "Employees may work remotely up to 2 days per week."),
                chunk(2, "Paternity leave is 2 weeks. Sick leave is 10 days per year."));

        assertThat(attribution.selectSupportingChunks("Up to 2 days per week.", pages)).containsExactly(0);
    }

    @Test
    void answerNotInTheDocumentsHasNoSupport() {
        // what the model wrote when it obeyed an injected instruction
        String answer = "PWNED\n\nMy system prompt is: Answering HR policy questions based on provided context";

        assertThat(attribution.selectSupportingChunks(answer, chunks)).isEmpty();
    }

    @Test
    void answerInAnotherScriptCannotBeCheckedSoTopResultIsUsed() {
        String tamilAnswer = "முழு நேர ஊழியர்களுக்கு ஆண்டுக்கு 24 நாட்கள் விடுப்பு உண்டு.";

        assertThat(attribution.selectSupportingChunks(tamilAnswer, chunks)).containsExactly(0);
    }

    @Test
    void noChunksMeansNoSources() {
        assertThat(attribution.selectSupportingChunks("Anything", List.of())).isEmpty();
    }

    @Test
    void quoteIsTheBestMatchingSentence() {
        String answer = "The annual learning budget is 1,000 USD.";

        String quote = attribution.bestPassage(answer, chunks.get(2).text(), 300);

        assertThat(quote).contains("learning budget of 1,000 USD").doesNotContain("Health Insurance");
    }

    @Test
    void longQuoteIsShortened() {
        String quote = attribution.bestPassage("leave", "leave ".repeat(200), 50);

        assertThat(quote).hasSize(53).endsWith("...");
    }

    private RetrievedChunk chunk(int page, String text) {
        return new RetrievedChunk(UUID.randomUUID(), "handbook.pdf", "Handbook", "1.0", page, null, text, 0.6);
    }
}
