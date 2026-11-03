package com.example.ragapp.conversation;

import static com.example.ragapp.TestPdfs.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Conversations, follow-up questions, feedback, export, delete, expiry and retention.
 * Uses the real model, so the Docker stack must be running:  docker compose up -d
 * Every test works in its own conversation. Test data uses its own tenant and is deleted at the end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationFlowTest {

    private static final String TENANT = "test-conv-tenant";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConversationService conversationService;

    private String documentId;

    @BeforeAll
    void uploadHandbook() throws Exception {
        byte[] handbook = pdf(
                List.of("Leave Policy", "All full-time employees are entitled to 24 days of paid annual leave per calendar year.",
                        "Up to 5 unused days may be carried forward to the next year."),
                List.of("Sick Leave", "Employees receive 10 days of paid sick leave per year.",
                        "A medical certificate is required when sick leave lasts more than 3 consecutive days."));

        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "handbook.pdf", "application/pdf", handbook))
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        documentId = objectMapper.readTree(body).get("id").asText();

        for (int attempt = 0; attempt < 120; attempt++) {
            String state = objectMapper.readTree(mockMvc.perform(get("/api/documents/" + documentId)
                            .header("X-Tenant-Id", TENANT)).andReturn().getResponse().getContentAsString())
                    .get("status").asText();
            if (state.equals("COMPLETED")) {
                return;
            }
            assertThat(state).isNotEqualTo("FAILED");
            Thread.sleep(500);
        }
        throw new AssertionError("Handbook was not processed in time");
    }

    @AfterAll
    void cleanUp() throws Exception {
        mockMvc.perform(delete("/api/documents/" + documentId).header("X-Tenant-Id", TENANT));
        jdbc.update("DELETE FROM conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM usage_records WHERE tenant_id = ?", TENANT);
    }

    @Test
    void firstQuestionCreatesAConversationWithBothMessagesSaved() throws Exception {
        JsonNode answer = ask(ALICE, "How many days of annual leave do employees get?", null);

        assertThat(answer.get("found").asBoolean()).isTrue();
        assertThat(answer.get("conversationId").asText()).isNotBlank();
        assertThat(answer.get("messageId").asLong()).isPositive();
        assertThat(answer.get("standaloneQuestion").asText()).isEqualTo("How many days of annual leave do employees get?");

        String id = answer.get("conversationId").asText();
        JsonNode messages = json(call(get("/api/conversations/" + id + "/messages"), ALICE).andExpect(status().isOk()));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("USER");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).get("sources").get(0).get("pageNumber").asInt()).isEqualTo(1);

        JsonNode list = json(call(get("/api/conversations"), ALICE).andExpect(status().isOk()));
        assertThat(list.toString()).contains(id);
        assertThat(list.toString()).contains("How many days of annual leave");
    }

    @Test
    void followUpQuestionIsRewrittenAndAnsweredFromTheDocuments() throws Exception {
        JsonNode first = ask(ALICE, "How many days of annual leave do employees get?", null);
        String conversationId = first.get("conversationId").asText();

        JsonNode followUp = ask(ALICE, "And what about sick leave?", conversationId);

        assertThat(followUp.get("conversationId").asText()).isEqualTo(conversationId);
        assertThat(followUp.get("standaloneQuestion").asText()).containsIgnoringCase("sick");
        assertThat(followUp.get("found").asBoolean()).isTrue();
        assertThat(followUp.get("answer").asText()).contains("10");
        assertThat(followUp.get("sources").get(0).get("pageNumber").asInt()).isEqualTo(2);

        JsonNode messages = json(call(get("/api/conversations/" + conversationId + "/messages"), ALICE));
        assertThat(messages).hasSize(4);
    }

    @Test
    void otherUsersCannotSeeChangeOrDeleteAConversation() throws Exception {
        JsonNode answer = ask(ALICE, "How many days of annual leave do employees get?", null);
        String id = answer.get("conversationId").asText();
        long messageId = answer.get("messageId").asLong();

        call(get("/api/conversations/" + id + "/messages"), BOB).andExpect(status().isNotFound());
        call(get("/api/conversations/" + id + "/export"), BOB).andExpect(status().isNotFound());
        call(delete("/api/conversations/" + id), BOB).andExpect(status().isNotFound());
        call(feedback(messageId, "UP", null, false), BOB).andExpect(status().isNotFound());
        call(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(chatBody("Follow up?", id)), BOB).andExpect(status().isNotFound());

        assertThat(json(call(get("/api/conversations"), BOB)).toString()).doesNotContain(id);
        call(get("/api/conversations/" + id + "/messages"), ALICE).andExpect(status().isOk());   // still there for Alice
    }

    @Test
    void feedbackIsSavedAndCanBeChanged() throws Exception {
        JsonNode answer = ask(ALICE, "How many days of annual leave do employees get?", null);
        String id = answer.get("conversationId").asText();
        long answerId = answer.get("messageId").asLong();

        call(feedback(answerId, "UP", null, false), ALICE).andExpect(status().isOk());
        call(feedback(answerId, "DOWN", "The number is outdated", true), ALICE).andExpect(status().isOk());

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM feedback WHERE message_id = ?", Integer.class, answerId);
        assertThat(rows).isEqualTo(1);   // the second feedback replaced the first
        assertThat(jdbc.queryForObject("SELECT reported_incorrect FROM feedback WHERE message_id = ?",
                Boolean.class, answerId)).isTrue();

        JsonNode messages = json(call(get("/api/conversations/" + id + "/messages"), ALICE));
        assertThat(messages.get(1).get("feedback").asText()).isEqualTo("DOWN");
    }

    @Test
    void feedbackInputIsValidated() throws Exception {
        JsonNode answer = ask(ALICE, "How many days of annual leave do employees get?", null);
        long answerId = answer.get("messageId").asLong();
        long questionId = answerId - 1;   // the question is saved just before its answer

        call(feedback(answerId, null, null, false), ALICE).andExpect(status().isBadRequest());
        call(feedback(answerId, "UP", "x".repeat(1001), false), ALICE).andExpect(status().isBadRequest());
        call(feedback(questionId, "UP", null, false), ALICE).andExpect(status().isBadRequest());   // only answers can be rated
        call(feedback(999_999_999L, "UP", null, false), ALICE).andExpect(status().isNotFound());
    }

    @Test
    void exportContainsTheWholeConversationAndDeleteRemovesEverything() throws Exception {
        JsonNode answer = ask(ALICE, "How many days of annual leave do employees get?", null);
        String id = answer.get("conversationId").asText();
        long answerId = answer.get("messageId").asLong();
        call(feedback(answerId, "UP", null, false), ALICE).andExpect(status().isOk());

        JsonNode export = json(call(get("/api/conversations/" + id + "/export"), ALICE)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("conversation-" + id))));
        assertThat(export.get("messages")).hasSize(2);
        assertThat(export.get("exportedAt").asText()).isNotBlank();

        call(delete("/api/conversations/" + id), ALICE).andExpect(status().isNoContent());

        call(get("/api/conversations/" + id + "/messages"), ALICE).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE conversation_id = ?::uuid",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE message_id = ?", Integer.class, answerId))
                .isZero();   // feedback is removed together with the conversation
    }

    @Test
    void idleConversationExpiresButCanStillBeRead() throws Exception {
        String id = ask(ALICE, "How many days of annual leave do employees get?", null).get("conversationId").asText();
        jdbc.update("UPDATE conversations SET last_activity_at = now() - interval '9 hours' WHERE id = ?::uuid", id);

        call(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody("Follow up?", id)), ALICE)
                .andExpect(status().isGone());
        call(get("/api/conversations/" + id + "/messages"), ALICE).andExpect(status().isOk());
        assertThat(json(call(get("/api/conversations"), ALICE)).toString()).contains("\"expired\":true");
    }

    @Test
    void retentionJobDeletesOldConversationsOnly() throws Exception {
        String old = ask(ALICE, "How many days of annual leave do employees get?", null).get("conversationId").asText();
        String recent = ask(ALICE, "How many days of sick leave do employees get?", null).get("conversationId").asText();
        jdbc.update("UPDATE conversations SET last_activity_at = now() - interval '31 days' WHERE id = ?::uuid", old);

        int deleted = conversationService.deleteIdleConversations();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        call(get("/api/conversations/" + old + "/messages"), ALICE).andExpect(status().isNotFound());
        call(get("/api/conversations/" + recent + "/messages"), ALICE).andExpect(status().isOk());
    }

    @Test
    void invalidUserHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/conversations").header("X-User-Id", "bad user!").header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/conversations").header("X-Tenant-Id", "bad/tenant"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownConversationIdIsNotFound() throws Exception {
        call(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(chatBody("Hello?", UUID.randomUUID().toString())), ALICE).andExpect(status().isNotFound());
    }

    // ----- helpers -----

    private JsonNode ask(String user, String question, String conversationId) throws Exception {
        return json(call(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(chatBody(question, conversationId)), user).andExpect(status().isOk()));
    }

    private String chatBody(String question, String conversationId) {
        return conversationId == null
                ? "{\"question\":\"" + question + "\"}"
                : "{\"question\":\"" + question + "\",\"conversationId\":\"" + conversationId + "\"}";
    }

    private MockHttpServletRequestBuilder feedback(long messageId, String rating, String comment, boolean incorrect) {
        StringBuilder body = new StringBuilder("{");
        if (rating != null) {
            body.append("\"rating\":\"").append(rating).append("\",");
        }
        if (comment != null) {
            body.append("\"comment\":\"").append(comment).append("\",");
        }
        body.append("\"reportedIncorrect\":").append(incorrect).append("}");
        return put("/api/messages/" + messageId + "/feedback").contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    private org.springframework.test.web.servlet.ResultActions call(MockHttpServletRequestBuilder request, String user)
            throws Exception {
        return mockMvc.perform(request.header("X-Tenant-Id", TENANT).header("X-User-Id", user));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
