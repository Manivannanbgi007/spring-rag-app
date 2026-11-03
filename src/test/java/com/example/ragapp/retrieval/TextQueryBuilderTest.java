package com.example.ragapp.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextQueryBuilderTest {

    @Test
    void anyJoinsTheWordsWithOr() {
        assertThat(TextQueryBuilder.any("How many days of annual leave?"))
                .isEqualTo("how | many | days | of | annual | leave");
    }

    @Test
    void allJoinsTheWordsWithAnd() {
        assertThat(TextQueryBuilder.all("How many days of annual leave?"))
                .isEqualTo("how & many & days & of & annual & leave");
    }

    @Test
    void keepsCodesAsSeparateWordsAndNumbers() {
        assertThat(TextQueryBuilder.any("What is form ZX-4471?")).isEqualTo("what | is | form | zx | 4471");
    }

    @Test
    void removesEverythingThatCouldChangeTheQuerySyntax() {
        String loose = TextQueryBuilder.any("'; DROP TABLE x; -- & | ! ( ) :* <->");
        String strict = TextQueryBuilder.all("'; DROP TABLE x; -- & | ! ( ) :* <->");

        assertThat(loose).isEqualTo("drop | table");     // one-letter words and all symbols are gone
        assertThat(strict).isEqualTo("drop & table");
        assertThat(loose + strict).doesNotContain("'").doesNotContain("!").doesNotContain(":").doesNotContain("(");
    }

    @Test
    void questionWithoutWordsGivesEmptyQuery() {
        assertThat(TextQueryBuilder.any("???")).isEmpty();
        assertThat(TextQueryBuilder.all("a b c")).isEmpty();
    }

    @Test
    void repeatedWordsCountOnceAndTheNumberOfWordsIsLimited() {
        assertThat(TextQueryBuilder.any("leave leave LEAVE")).isEqualTo("leave");
        assertThat(TextQueryBuilder.any("aa bb cc dd ee ff gg hh ii jj kk ll mm nn").split(" \\| ")).hasSize(12);
    }
}
