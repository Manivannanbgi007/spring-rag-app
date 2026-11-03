package com.example.ragapp.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Limits per user, per tenant and for uploads, and the size limit for JSON requests.
 * The limits are made small here: 3 questions per user per minute, 5 per tenant, 2 uploads per hour.
 * The questions are about something no document contains, so no model is called (only the embedding,
 * and that is cached), which keeps the test fast.
 */
@SpringBootTest(properties = {
        "app.rate-limit.user-per-minute=3",
        "app.rate-limit.tenant-per-minute=5",
        "app.rate-limit.uploads-per-hour-per-tenant=2"
})
@AutoConfigureMockMvc
class RateLimitTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private void cleanUp() {
        jdbc.update("DELETE FROM conversations WHERE tenant_id LIKE 'ratelimit-%'");
        jdbc.update("DELETE FROM usage_records WHERE tenant_id LIKE 'ratelimit-%'");
    }

    private ResultActions ask(String tenant, String user) throws Exception {
        return mockMvc.perform(post("/api/chat")
                .header("X-Tenant-Id", tenant).header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"zzzqqq nothing matches this\"}"));
    }

    @Test
    void aUserAskingTooOftenGetsTooManyRequestsWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            ask("ratelimit-a", "fast-user").andExpect(status().isOk());
        }

        ask("ratelimit-a", "fast-user")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.detail").value("You are asking questions too quickly. Please wait a moment."));

        // another user of the same tenant is not affected
        ask("ratelimit-a", "calm-user").andExpect(status().isOk());

        cleanUp();
    }

    @Test
    void aTenantHasItsOwnLimitForAllItsUsers() throws Exception {
        // 5 questions per tenant: 3 from one user, 2 from another. The 6th (from a third user) is refused.
        for (int i = 0; i < 3; i++) {
            ask("ratelimit-b", "user-1").andExpect(status().isOk());
        }
        for (int i = 0; i < 2; i++) {
            ask("ratelimit-b", "user-2").andExpect(status().isOk());
        }

        ask("ratelimit-b", "user-3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("your organisation")));

        // other tenants are not affected
        ask("ratelimit-c", "user-1").andExpect(status().isOk());

        cleanUp();
    }

    @Test
    void uploadsHaveTheirOwnHourlyLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            upload("ratelimit-d").andExpect(status().isBadRequest());   // not a real document: rejected, but it counts
        }

        upload("ratelimit-d").andExpect(status().isTooManyRequests());
        upload("ratelimit-e").andExpect(status().isBadRequest());       // another tenant still can
    }

    @Test
    void readingIsNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/conversations").header("X-Tenant-Id", "ratelimit-f").header("X-User-Id", "reader"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void anOversizedJsonRequestIsRejectedBeforeItIsRead() throws Exception {
        String hugeQuestion = "a".repeat(70 * 1024);

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + hugeQuestion + "\"}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("too large")));
    }

    private ResultActions upload(String tenant) throws Exception {
        return mockMvc.perform(multipart("/api/documents")
                .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                .header("X-Tenant-Id", tenant));
    }

    @Test
    void sanityCheckOfTheConfiguration(@Autowired RateLimitProperties properties) {
        assertThat(properties.userPerMinute()).isEqualTo(3);
        assertThat(properties.tenantPerMinute()).isEqualTo(5);
    }
}
