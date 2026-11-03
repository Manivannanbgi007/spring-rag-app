package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmJudgeParsingTest {

    @Test
    void readsTheVerdictOfTheJudge() {
        assertThat(LlmJudge.parse("SUPPORTED")).isTrue();
        assertThat(LlmJudge.parse("  supported.\n")).isTrue();
        assertThat(LlmJudge.parse("UNSUPPORTED")).isFalse();   // contains the word SUPPORTED: must still be false
        assertThat(LlmJudge.parse("The answer is not supported by the evidence.")).isFalse();
    }

    @Test
    void noClearVerdictIsNotGuessed() {
        assertThat(LlmJudge.parse("I am not sure")).isNull();
        assertThat(LlmJudge.parse("")).isNull();
        assertThat(LlmJudge.parse(null)).isNull();
    }
}
