package com.example.ragapp.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.privacy.MaskingSession;
import com.example.ragapp.privacy.PiiMasker;
import com.example.ragapp.privacy.PrivacyProperties;
import com.example.ragapp.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Checks what the language model would be sent: no real personal data, and it can be restored. */
class PromptAssemblerTest {

    private final PromptAssembler assembler = new PromptAssembler(new ClassPathResource("prompts/rag-user.st"));
    private final PiiMasker masker = new PiiMasker(new PrivacyProperties(true));

    @Test
    void promptContainsPlaceholdersInsteadOfPersonalData() {
        MaskingSession pii = masker.newSession();
        String safeQuestion = pii.mask("My number is 9876543210. Who do I contact about leave?");
        ContextBuilder.BuiltContext context = new ContextBuilder.BuiltContext(
                "[1] Handbook (page 4)\nContact HR at hr-help@acme.com or +91 44 2345 6789.\n\n",
                List.of(new RetrievedChunk(UUID.randomUUID(), "h.pdf", "Handbook", "1", 4, null, "text", 0.6)));

        String prompt = assembler.assemble(context, safeQuestion, pii);

        assertThat(prompt).doesNotContain("hr-help@acme.com").doesNotContain("9876543210").doesNotContain("2345 6789");
        assertThat(prompt).contains("[EMAIL_1]").contains("[PHONE_1]").contains("[PHONE_2]");
        assertThat(prompt).contains("Who do I contact about leave?");   // normal words are untouched
    }

    @Test
    void irregularSpacingFromPdfsDoesNotHidePersonalData() {
        // real text extracted from a PDF: extra spaces inside the phone number
        String pdfText = "ACME   Corp   HR  Contacts\n   The  HR  helpdesk  phone  number   is +91  44 2345  6789   and open.\n"
                + "     Call the duty manager on 9876543210.   ";
        RetrievedChunk chunk = new RetrievedChunk(UUID.randomUUID(), "h.pdf", "HR Contacts", "1", 1, null, pdfText, 0.6);
        ContextBuilder builder = new ContextBuilder(
                new com.example.ragapp.retrieval.RagProperties(5, 0.4, 8000, 200, true, true, 20, 10, 60),
                new PromptInjectionFilter(), com.example.ragapp.observability.RagMetrics.forTests());
        MaskingSession pii = masker.newSession();

        String prompt = assembler.assemble(builder.build(List.of(chunk)), pii.mask("Phone?"), pii);

        assertThat(prompt).doesNotContain("2345").doesNotContain("9876543210");
        assertThat(prompt).contains("[PHONE_1]").contains("[PHONE_2]");
        assertThat(prompt).doesNotContain("  ");   // no runs of spaces left
    }

    @Test
    void theUserSeesTheRealValuesInTheAnswer() {
        MaskingSession pii = masker.newSession();
        ContextBuilder.BuiltContext context = new ContextBuilder.BuiltContext(
                "[1] Handbook (page 4)\nContact HR at hr-help@acme.com.\n\n", List.of());
        assembler.assemble(context, pii.mask("Who do I contact?"), pii);

        String modelAnswer = "Contact HR at [EMAIL_1].";

        assertThat(pii.restore(modelAnswer)).isEqualTo("Contact HR at hr-help@acme.com.");
    }
}
