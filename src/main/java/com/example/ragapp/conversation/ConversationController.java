package com.example.ragapp.conversation;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.common.Caller;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversation history, feedback, export and delete.
 * (Asking questions is in ChatController.) The user comes from the X-User-Id header.
 */
@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversationService;
    private final FeedbackService feedbackService;

    public ConversationController(ConversationService conversationService, FeedbackService feedbackService) {
        this.conversationService = conversationService;
        this.feedbackService = feedbackService;
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> list(Caller caller) {
        return conversationService.list(caller);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageResponse> messages(Caller caller, @PathVariable UUID id) {
        return conversationService.getMessages(caller, id);
    }

    /** Downloads the whole conversation as a JSON file. */
    @GetMapping("/conversations/{id}/export")
    public ResponseEntity<ConversationExport> export(Caller caller, @PathVariable UUID id) {
        ConversationExport export = conversationService.export(caller, id);
        ContentDisposition download = ContentDisposition.attachment()
                .filename("conversation-" + id + ".json")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, download.toString())
                .body(export);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(Caller caller, @PathVariable UUID id) {
        conversationService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }

    /** Rate an answer. The messageId is the "messageId" returned with the answer. */
    @PutMapping("/messages/{messageId}/feedback")
    public FeedbackResponse feedback(Caller caller, @PathVariable long messageId,
                                     @Valid @RequestBody FeedbackRequest request) {
        return feedbackService.submit(caller, messageId, request);
    }
}
