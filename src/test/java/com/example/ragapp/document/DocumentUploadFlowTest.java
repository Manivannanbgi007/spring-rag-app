package com.example.ragapp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full flow through the real application: upload -> background processing -> chunks in pgvector.
 * Uses the real database and the real Ollama embedding model, so the Docker stack must be running:
 *     docker compose up -d
 * Test data uses its own tenant ("test-tenant") and is deleted after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentUploadFlowTest {

    private static final String TENANT = "test-tenant";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (String id : createdIds) {
            waitUntilFinished(id);
            mockMvc.perform(delete("/api/documents/" + id).header("X-Tenant-Id", TENANT));
        }
    }

    @Test
    void pdfIsProcessedPageByPage() throws Exception {
        byte[] pdf = createPdf(
                "Annual leave policy. Every employee gets 24 days of paid leave. " + UUID.randomUUID(),
                "Sick leave policy. Employees get 10 days of sick leave per year.",
                "Remote work policy. Employees may work from home two days a week.");

        JsonNode uploaded = upload("leave.pdf", pdf, "Leave Policy", "2025-01");
        assertThat(uploaded.get("status").asText()).isEqualTo("QUEUED");
        String id = uploaded.get("id").asText();

        JsonNode finished = waitUntilFinished(id);
        assertThat(finished.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.get("pageCount").asInt()).isEqualTo(3);
        assertThat(finished.get("chunkCount").asInt()).isGreaterThanOrEqualTo(3);

        // chunks are in pgvector with page numbers and metadata
        List<String> pages = jdbc.queryForList(
                "SELECT DISTINCT metadata->>'page_number' FROM vector_store WHERE metadata->>'document_id' = ?",
                String.class, id);
        assertThat(pages).containsExactlyInAnyOrder("1", "2", "3");
        String fileName = jdbc.queryForObject(
                "SELECT metadata->>'file_name' FROM vector_store WHERE metadata->>'document_id' = ? LIMIT 1",
                String.class, id);
        assertThat(fileName).isEqualTo("leave.pdf");
    }

    @Test
    void docxIsSplitByHeadingAndTablesAreRead() throws Exception {
        byte[] docx = createDocx();

        String id = upload("benefits.docx", docx, null, "1.0").get("id").asText();

        JsonNode finished = waitUntilFinished(id);
        assertThat(finished.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.get("title").asText()).isEqualTo("benefits");   // title taken from file name

        List<String> sections = jdbc.queryForList(
                "SELECT DISTINCT metadata->>'section' FROM vector_store WHERE metadata->>'document_id' = ?",
                String.class, id);
        assertThat(sections).contains("Leave Policy > Annual Leave", "Leave Policy > Sick Leave");

        Integer tableChunks = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ? AND content LIKE '%Grade A | 30 days%'",
                Integer.class, id);
        assertThat(tableChunks).isEqualTo(1);
    }

    @Test
    void sameFileUploadedTwiceIsRejected() throws Exception {
        byte[] pdf = createPdf("Unique policy text " + UUID.randomUUID());
        String id = upload("a.pdf", pdf, null, "1.0").get("id").asText();

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "copy-of-a.pdf", "application/pdf", pdf))
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingDocumentId").value(id));
    }

    @Test
    void deleteRemovesDocumentAndChunks() throws Exception {
        String id = upload("d.pdf", createPdf("Delete me " + UUID.randomUUID()), null, "1.0").get("id").asText();
        waitUntilFinished(id);

        mockMvc.perform(delete("/api/documents/" + id).header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent());

        Integer chunks = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?", Integer.class, id);
        assertThat(chunks).isZero();
        mockMvc.perform(get("/api/documents/" + id).header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherTenantCannotSeeTheDocument() throws Exception {
        String id = upload("t.pdf", createPdf("Tenant text " + UUID.randomUUID()), null, "1.0").get("id").asText();

        mockMvc.perform(get("/api/documents/" + id).header("X-Tenant-Id", "another-tenant"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pdfWithoutTextFailsWithClearMessage() throws Exception {
        // a page with no text, like a scanned image
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.getDocumentInformation().setTitle(UUID.randomUUID().toString());   // makes the file unique
            pdf.save(out);
        }
        String id = upload("scan.pdf", out.toByteArray(), null, "1.0").get("id").asText();

        JsonNode finished = waitUntilFinished(id);
        assertThat(finished.get("status").asText()).isEqualTo("FAILED");
        assertThat(finished.get("statusMessage").asText()).contains("No readable text");
    }

    @Test
    void invalidUploadsAreRejected() throws Exception {
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a pdf".getBytes())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/api/documents"))
                .andExpect(status().isBadRequest());
    }

    // ----- helpers -----

    private JsonNode upload(String fileName, byte[] content, String title, String version) throws Exception {
        var request = multipart("/api/documents")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                .param("version", version)
                .header("X-Tenant-Id", TENANT);
        if (title != null) {
            request.param("title", title);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        createdIds.add(json.get("id").asText());
        return json;
    }

    /** Polls the status endpoint (like a real client would) until processing ends. */
    private JsonNode waitUntilFinished(String id) throws Exception {
        for (int attempt = 0; attempt < 240; attempt++) {
            var response = mockMvc.perform(get("/api/documents/" + id).header("X-Tenant-Id", TENANT))
                    .andReturn().getResponse();
            if (response.getStatus() == 404) {
                return null;   // already deleted
            }
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            String status = json.path("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return json;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Document " + id + " did not finish in time");
    }

    private byte[] createPdf(String... pageTexts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.newLineAtOffset(40, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }

    private byte[] createDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument docx = new XWPFDocument()) {
            addParagraph(docx, "Heading1", "Leave Policy");
            addParagraph(docx, "Heading2", "Annual Leave");
            addParagraph(docx, null, "Full time employees get 24 days of annual leave. " + UUID.randomUUID());
            addParagraph(docx, "Heading2", "Sick Leave");
            addParagraph(docx, null, "Employees get 10 days of paid sick leave.");

            XWPFTable table = docx.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Grade");
            table.getRow(0).getCell(1).setText("Leave");
            table.getRow(1).getCell(0).setText("Grade A");
            table.getRow(1).getCell(1).setText("30 days");
            docx.write(out);
        }
        return out.toByteArray();
    }

    private void addParagraph(XWPFDocument docx, String style, String text) {
        XWPFParagraph paragraph = docx.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
    }
}
