package com.example.ragapp.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A mistake of the caller must be answered with a 4xx error that says what is wrong,
 * never with "500 Something went wrong" (that is reserved for our own faults).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownAddressIsNotFound() throws Exception {
        mockMvc.perform(get("/api/nothing-here")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/")).andExpect(status().isNotFound());   // an empty id
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/documents")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/chat")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        mockMvc.perform(delete("/api/documents/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The value of 'id' is not valid."));
        mockMvc.perform(get("/api/conversations/12345/messages")).andExpect(status().isBadRequest());
    }

    @Test
    void wrongContentTypeIsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void errorBodiesAreAlwaysProblemJsonWithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/api/nothing-here"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }
}
