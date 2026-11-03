package com.example.ragapp.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker(new PrivacyProperties(true));

    @Test
    void emailIsMaskedAndRestored() {
        MaskingSession pii = masker.newSession();

        String masked = pii.mask("Please write to maria.k@acme-corp.co.in about leave.");

        assertThat(masked).isEqualTo("Please write to [EMAIL_1] about leave.");
        assertThat(pii.restore("Contact [EMAIL_1] for details.")).isEqualTo("Contact maria.k@acme-corp.co.in for details.");
    }

    @Test
    void sameValueGetsTheSamePlaceholderAndDifferentValuesGetNewOnes() {
        MaskingSession pii = masker.newSession();

        String masked = pii.mask("a@x.com wrote to b@x.com and then to a@x.com again");

        assertThat(masked).isEqualTo("[EMAIL_1] wrote to [EMAIL_2] and then to [EMAIL_1] again");
        assertThat(pii.maskedValueCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+91 98765 43210", "+1 (555) 123-4567", "555-123-4567", "9876543210", "(555) 123-4567"})
    void phoneNumbersAreMasked(String phone) {
        MaskingSession pii = masker.newSession();

        assertThat(pii.mask("Call me on " + phone + " tomorrow.")).isEqualTo("Call me on [PHONE_1] tomorrow.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Full-time employees get 24 days of leave.",
            "Core hours are 10:00 to 16:00 local time.",
            "The learning budget is 1,000 USD per year.",
            "The policy took effect on 2025-01-15.",
            "Overtime is paid at 1.5 times the normal rate.",
            "Maternity leave is 26 weeks, see section 5 and page 12.",
            "Claims over 300000 rupees need approval.",
            "Version 2.0.1 of the form HR-204 applies."})
    void normalPolicyTextIsLeftAlone(String text) {
        MaskingSession pii = masker.newSession();

        assertThat(pii.mask(text)).isEqualTo(text);
        assertThat(pii.hasMaskedValues()).isFalse();
    }

    @Test
    void cardNumbersAreMaskedOnlyWhenTheChecksumIsValid() {
        MaskingSession pii = masker.newSession();

        assertThat(pii.mask("Card 4111 1111 1111 1111 expires soon")).isEqualTo("Card [CARD_1] expires soon");
        assertThat(pii.mask("Order number 4111111111111112 shipped")).isEqualTo("Order number 4111111111111112 shipped");
    }

    @Test
    void governmentIdsAreMasked() {
        MaskingSession pii = masker.newSession();

        assertThat(pii.mask("Aadhaar 2345 6789 0123, SSN 123-45-6789, PAN ABCDE1234F"))
                .isEqualTo("Aadhaar [ID_1], SSN [ID_2], PAN [ID_3]");
    }

    @Test
    void secretsAreMasked() {
        MaskingSession pii = masker.newSession();

        assertThat(pii.mask("my password is hunter2 ok")).isEqualTo("my [SECRET_1] ok");
        assertThat(pii.mask("api key: sk-abcdefghijklmnopqrstuvwx")).isEqualTo("[SECRET_2]");
        assertThat(pii.mask("aws AKIAABCDEFGHIJKLMNOP")).isEqualTo("aws [SECRET_3]");
        assertThat(pii.restore("[SECRET_1]")).isEqualTo("password is hunter2");
    }

    @Test
    void nothingIsMaskedWhenSwitchedOff() {
        MaskingSession pii = new PiiMasker(new PrivacyProperties(false)).newSession();

        assertThat(pii.mask("Mail a@x.com")).isEqualTo("Mail a@x.com");
        assertThat(pii.hasMaskedValues()).isFalse();
    }

    @Test
    void unknownPlaceholdersAndNullAreLeftAlone() {
        MaskingSession pii = masker.newSession();
        pii.mask("a@x.com");

        assertThat(pii.restore("Text with [EMAIL_9] and [1]")).isEqualTo("Text with [EMAIL_9] and [1]");
        assertThat(pii.restore(null)).isNull();
        assertThat(pii.mask(null)).isNull();
    }

    // ----- streaming -----

    @Test
    void placeholderSplitAcrossPiecesIsRestored() {
        MaskingSession pii = masker.newSession();
        pii.mask("maria@acme.com");
        StreamingRestorer restorer = pii.streamingRestorer();

        StringBuilder shown = new StringBuilder();
        for (String piece : new String[] {"Write", " to [EM", "AIL", "_1]", " now."}) {
            shown.append(restorer.feed(piece));
        }
        shown.append(restorer.flush());

        assertThat(shown.toString()).isEqualTo("Write to maria@acme.com now.");
    }

    @Test
    void piecesAreNotShownWhileTheyMightBeAPlaceholder() {
        MaskingSession pii = masker.newSession();
        pii.mask("maria@acme.com");
        StreamingRestorer restorer = pii.streamingRestorer();

        assertThat(restorer.feed("See [EM")).isEqualTo("See ");   // "[EM" is held back
        assertThat(restorer.feed("AIL_1].")).isEqualTo("maria@acme.com.");
    }

    @Test
    void squareBracketsThatAreNotPlaceholdersComeThroughAndNothingIsLost() {
        MaskingSession pii = masker.newSession();
        pii.mask("maria@acme.com");
        StreamingRestorer restorer = pii.streamingRestorer();

        String shown = restorer.feed("see [") + restorer.feed("1] note") + restorer.flush();

        assertThat(shown).isEqualTo("see [1] note");
    }

    @Test
    void textPassesStraightThroughWhenNothingWasMasked() {
        StreamingRestorer restorer = masker.newSession().streamingRestorer();

        assertThat(restorer.feed("a [b")).isEqualTo("a [b");
        assertThat(restorer.flush()).isEmpty();
    }
}
