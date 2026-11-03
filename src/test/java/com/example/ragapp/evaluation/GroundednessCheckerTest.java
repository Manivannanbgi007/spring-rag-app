package com.example.ragapp.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class GroundednessCheckerTest {

    private final GroundednessChecker checker = new GroundednessChecker();

    private final List<String> evidence = List.of(
            "All full-time employees are entitled to 24 days of paid annual leave per calendar year. "
                    + "Up to 5 unused days may be carried forward to the next year.",
            "Each employee has an annual learning budget of 1,000 USD for courses and conferences.");

    @Test
    void anAnswerThatRepeatsTheDocumentIsGrounded() {
        var result = checker.check("Full-time employees are entitled to 24 days of paid annual leave per year.", evidence);

        assertThat(result.totalClaims()).isEqualTo(1);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.unsupportedClaims()).isEmpty();
    }

    @Test
    void aWrongNumberMakesTheClaimUnsupported() {
        var result = checker.check("Full-time employees are entitled to 30 days of paid annual leave per year.", evidence);

        assertThat(result.score()).isZero();
        assertThat(result.unsupportedClaims()).hasSize(1);
    }

    @Test
    void aMadeUpFactMakesTheClaimUnsupported() {
        var result = checker.check("The company offers free lunch for every employee on Fridays.", evidence);

        assertThat(result.score()).isZero();
    }

    @Test
    void oneGoodAndOneMadeUpClaimGiveHalfTheScore() {
        var result = checker.check("""
                Employees get 24 days of paid annual leave.
                The company also pays for a gym membership.""", evidence);

        assertThat(result.totalClaims()).isEqualTo(2);
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.unsupportedClaims()).containsExactly("The company also pays for a gym membership.");
    }

    @Test
    void numbersAreComparedByValueNotByWritingStyle() {
        assertThat(checker.check("The annual learning budget is 1000 USD for courses.", evidence).score()).isEqualTo(1.0);
        assertThat(checker.check("The annual learning budget is 1,000 USD for courses.", evidence).score()).isEqualTo(1.0);
        assertThat(checker.check("The annual learning budget is 2,000 USD for courses.", evidence).score()).isZero();
    }

    @Test
    void listItemsAreCheckedOneByOne() {
        var result = checker.check("""
                * Employees receive 24 days of paid annual leave.
                * Up to 5 unused days may be carried forward.
                * Employees receive a company car.""", evidence);

        assertThat(result.totalClaims()).isEqualTo(3);
        assertThat(result.supportedClaims()).isEqualTo(2);
    }

    @Test
    void aNumberFoundSomewhereElseOnThePageDoesNotSupportTheClaim() {
        // found by the judge calibration: the page heading contains "3.", so a page-wide check let "3 days" pass
        List<String> page = List.of("3. Work Arrangements\nEmployees may work remotely up to 2 days per week with manager approval.\n"
                + "Core collaboration hours are 10:00 to 16:00 local time.");

        assertThat(checker.check("Employees may work remotely up to 2 days per week.", page).score()).isEqualTo(1.0);
        assertThat(checker.check("Employees may work remotely up to 3 days per week.", page).score()).isZero();
    }

    @Test
    void wrongMonthsAreNotSupported() {
        List<String> page = List.of("Performance reviews take place twice a year, in June and December.");

        assertThat(checker.check("Performance reviews take place twice a year, in June and December.", page).score()).isEqualTo(1.0);
        assertThat(checker.check("Performance reviews take place twice a year, in March and September.", page).score()).isZero();
    }

    @Test
    void aClaimThatCombinesTwoEvidenceSentencesIsStillSupported() {
        List<String> page = List.of("Employees receive 10 days of paid sick leave per year. "
                + "A medical certificate is required when sick leave lasts more than 3 consecutive days.");

        var result = checker.check("Employees receive 10 days of paid sick leave, and need a medical certificate after 3 days.", page);

        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void contactDetailsWithPunctuationAreRecognised() {
        // seen in the quality evaluation (case A22): an answer that is only a phone number
        List<String> page = List.of("For questions about leave or benefits, contact the HR helpdesk at hr-help@northwind.example.\n"
                + "The HR helpdesk phone number is +1 (555) 010-4477 and is open from 9:00 to 17:00.");

        assertThat(checker.check("The HR helpdesk phone number is +1 (555) 010-4477.", page).score()).isEqualTo(1.0);
        // "reach" is a paraphrase, but the phone number matches digit for digit, and exact items count for a lot
        assertThat(checker.check("You can reach the HR helpdesk on +1 (555) 010-4477.", page).score()).isEqualTo(1.0);
        assertThat(checker.check("You can write to the HR helpdesk at hr-help@northwind.example.", page).score()).isEqualTo(1.0);
    }

    @Test
    void anAnswerThatIsOnlyAPhoneNumberOrAnAddressIsFoundInTheRightSentence() {
        // case A22 of the quality evaluation: no ordinary words at all, so the sentence must be found by its numbers
        List<String> page = List.of("For questions about leave or benefits, contact the HR helpdesk at hr-help@northwind.example.\n"
                + "The HR helpdesk phone number is +1 (555) 010-4477 and is open from 9:00 to 17:00.");

        assertThat(checker.check("+1 (555) 010-4477", page).score()).isEqualTo(1.0);
        assertThat(checker.check("+1 (555) 010-4999", page).score()).isZero();
        assertThat(checker.check("hr-help@northwind.example", page).score()).isEqualTo(1.0);
    }

    @Test
    void aWrongPhoneNumberOrEmailAddressIsNotSupported() {
        List<String> page = List.of("For questions about leave or benefits, contact the HR helpdesk at hr-help@northwind.example.\n"
                + "The HR helpdesk phone number is +1 (555) 010-4477 and is open from 9:00 to 17:00.");

        assertThat(checker.check("The HR helpdesk phone number is +1 (555) 010-4478.", page).score()).isZero();
        assertThat(checker.check("You can write to the HR helpdesk at hr-help@other.example.", page).score()).isZero();
    }

    @Test
    void knownLimit_aCorrectAnswerWithManyDescriptiveWordsFromOutsideTheTextIsFlagged() {
        List<String> page = List.of("For questions about leave or benefits, contact the HR helpdesk at hr-help@northwind.example.");

        // Correct, but "email address" is not in the text (it says "contact ... at"). A word comparison cannot
        // know that these mean the same, so the claim is flagged. This is what the judge model is for.
        assertThat(checker.check("The email address of the HR helpdesk is hr-help@northwind.example.", page).score()).isZero();
    }

    @Test
    void shortRepliesWithoutClaimsAreNotJudged() {
        assertThat(checker.check("Yes.", evidence).totalClaims()).isZero();
        assertThat(checker.check("Yes.", evidence).score()).isEqualTo(1.0);
    }
}
