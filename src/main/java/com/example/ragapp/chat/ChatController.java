package com.example.ragapp.chat;

import com.example.ragapp.common.Caller;
import com.example.ragapp.resilience.RateLimitService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Chat API. Two ways to ask the same question:
 *   POST /api/chat         -> waits, then returns the complete answer as JSON
 *   POST /api/chat/stream  -> streams the answer as it is written (Server-Sent Events)
 *
 * Send "conversationId" (from an earlier answer) to ask a follow-up question.
 * Headers: X-Tenant-Id (which documents) and X-User-Id (whose conversations).
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final RateLimitService rateLimitService;

    public ChatController(ChatService chatService, RateLimitService rateLimitService) {
        this.chatService = chatService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping
    public ChatResponse ask(Caller caller, @Valid @RequestBody ChatRequest request) {
        rateLimitService.checkQuestion(caller);
        return chatService.ask(caller, request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(Caller caller, @Valid @RequestBody ChatRequest request) {
        rateLimitService.checkQuestion(caller);
        return chatService.stream(caller, request)
                .map(event -> ServerSentEvent.builder(event.data()).event(event.type()).build());
    }
}
