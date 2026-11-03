package com.example.ragapp.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.example.ragapp.conversation.ChatTurn;
import com.example.ragapp.conversation.MessageRole;
import org.junit.jupiter.api.Test;

/** The model output is checked before it is used. No Spring, no model. */
class QuestionRewriterTest {

    private static final String ORIGINAL = "What about sick leave?";

    @Test
    void usesTheRewrittenQuestion() {
        assertThat(QuestionRewriter.pickSafeResult("How many days of sick leave do employees get?", ORIGINAL))
                .isEqualTo("How many days of sick leave do employees get?");
    }

    @Test
    void removesLabelsAndQuotes() {
        assertThat(QuestionRewriter.pickSafeResult("Rewritten question: \"How many sick days?\"", ORIGINAL))
                .isEqualTo("How many sick days?");
    }

    @Test
    void removesSpeakerLabelCopiedFromTheHistory() {
        // real output of the model
        assertThat(QuestionRewriter.pickSafeResult("User: How many days a week do I get for remote work?", ORIGINAL))
                .isEqualTo("How many days a week do I get for remote work?");
    }

    @Test
    void takesOnlyTheFirstLine() {
        assertThat(QuestionRewriter.pickSafeResult("How many sick days?\n\nExplanation: I replaced ...", ORIGINAL))
                .isEqualTo("How many sick days?");
    }

    @Test
    void fallsBackToTheOriginalWhenOutputIsUnusable() {
        assertThat(QuestionRewriter.pickSafeResult(null, ORIGINAL)).isEqualTo(ORIGINAL);
        assertThat(QuestionRewriter.pickSafeResult("   ", ORIGINAL)).isEqualTo(ORIGINAL);
        assertThat(QuestionRewriter.pickSafeResult("x".repeat(500), ORIGINAL)).isEqualTo(ORIGINAL);
    }

    @Test
    void rejectsAnAnswerWrittenInsteadOfAQuestion() {
        // real output of the model for "how many days a week?"
        assertThat(QuestionRewriter.pickSafeResult(
                "Employees may work remotely up to 2 days per week with manager approval.", "how many days a week?"))
                .isEqualTo("how many days a week?");
    }

    @Test
    void rejectsRewritesThatTalkAboutTheConversation() {
        // real output of the model for "What is the dress code?"
        assertThat(QuestionRewriter.pickSafeResult(
                "Is there a specific dress code mentioned in the conversation?", "What is the dress code?"))
                .isEqualTo("What is the dress code?");
    }

    @Test
    void rejectsARewriteThatJustRepeatsAnEarlierQuestion() {
        // real output of the model in the quality evaluation (case F01)
        List<ChatTurn> history = List.of(
                new ChatTurn(MessageRole.USER, "How many days of paid sick leave do employees receive per year?"),
                new ChatTurn(MessageRole.ASSISTANT, "10 days of paid sick leave per year."));

        assertThat(QuestionRewriter.pickSafeResult(
                "How many days of paid sick leave do employees receive per year?",
                "And how long is maternity leave?", history))
                .isEqualTo("And how long is maternity leave?");
    }

    @Test
    void rejectsARewriteThatLostTheNewWordsOfTheQuestion() {
        List<ChatTurn> history = List.of(
                new ChatTurn(MessageRole.USER, "How many days of annual leave do I get?"),
                new ChatTurn(MessageRole.ASSISTANT, "24 days."));

        // "carry over" is what the user asks for; this rewrite forgot it
        assertThat(QuestionRewriter.pickSafeResult("How many days of annual leave are there?",
                "How many can I carry over?", history)).isEqualTo("How many can I carry over?");
    }

    @Test
    void keepsAGoodRewriteOfAFollowUp() {
        List<ChatTurn> history = List.of(
                new ChatTurn(MessageRole.USER, "How long is maternity leave?"),
                new ChatTurn(MessageRole.ASSISTANT, "26 weeks on full pay."));

        assertThat(QuestionRewriter.pickSafeResult("How long is paternity leave?", "What about paternity leave?", history))
                .isEqualTo("How long is paternity leave?");
        assertThat(QuestionRewriter.pickSafeResult("Is maternity leave paid?", "Is it paid?", history))
                .isEqualTo("Is maternity leave paid?");
    }

    @Test
    void statementsAreFineWhenTheOriginalWasNotAQuestion() {
        assertThat(QuestionRewriter.pickSafeResult("Tell me about paternity leave", "and paternity leave"))
                .isEqualTo("Tell me about paternity leave");
    }

    @Test
    void historyIsFormattedAndLongMessagesAreShortened() {
        String history = QuestionRewriter.formatHistory(List.of(
                new ChatTurn(MessageRole.USER, "How many days of annual leave?"),
                new ChatTurn(MessageRole.ASSISTANT, "word ".repeat(200))));

        assertThat(history).startsWith("User: How many days of annual leave?\nAssistant: word word");
        assertThat(history).endsWith("...");
        assertThat(history.length()).isLessThan(400);
    }
}
