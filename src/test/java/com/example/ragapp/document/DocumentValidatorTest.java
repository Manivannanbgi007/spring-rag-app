package com.example.ragapp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import com.example.ragapp.common.InvalidDocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Plain unit test: no Spring, no database, runs in milliseconds. */
class DocumentValidatorTest {

    private final DocumentValidator validator =
            new DocumentValidator(new DocumentProperties("./uploads", 500, 255));

    @Test
    void acceptsRealPdf() {
        MockMultipartFile file = file("policy.pdf", "%PDF-1.7 fake body");
        assertThat(validator.validateFile(file)).isEqualTo(DocumentType.PDF);
    }

    @Test
    void acceptsDocxZipHeader() {
        MockMultipartFile file = new MockMultipartFile("file", "policy.docx", "application/octet-stream",
                new byte[] {'P', 'K', 3, 4, 0, 0});
        assertThat(validator.validateFile(file)).isEqualTo(DocumentType.DOCX);
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validator.validateFile(file("policy.pdf", "")))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validator.validateFile(file("notes.txt", "hello")))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Only PDF and DOCX");
    }

    @Test
    void rejectsTextFileRenamedToPdf() {
        assertThatThrownBy(() -> validator.validateFile(file("fake.pdf", "just some text")))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void removesFolderPartsFromFileName() {
        assertThat(validator.cleanFileName("../../etc/evil.pdf")).isEqualTo("evil.pdf");
        assertThat(validator.cleanFileName("C:\\temp\\leave policy.pdf")).isEqualTo("leave policy.pdf");
    }

    @Test
    void usesFileNameAsTitleWhenNoTitleGiven() {
        assertThat(validator.resolveTitle(null, "Leave Policy.pdf")).isEqualTo("Leave Policy");
        assertThat(validator.resolveTitle("  Custom  ", "x.pdf")).isEqualTo("Custom");
    }

    @Test
    void rejectsBadTenantAndVersion() {
        assertThatThrownBy(() -> validator.validateTenant("a/b")).isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> validator.validateVersion("v 1!")).isInstanceOf(InvalidDocumentException.class);
        assertThat(validator.validateVersion("2025-01")).isEqualTo("2025-01");
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream",
                content.getBytes(StandardCharsets.US_ASCII));
    }
}
