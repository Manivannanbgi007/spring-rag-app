package com.example.ragapp.conversation;

import com.example.ragapp.common.Caller;
import com.example.ragapp.common.ConversationNotFoundException;
import com.example.ragapp.common.InvalidRequestException;
import com.example.ragapp.observability.RagMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Saves thumbs up/down and "this answer is wrong" reports. */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final FeedbackRepository feedbackRepository;
    private final RagMetrics metrics;

    public FeedbackService(ChatMessageRepository messages, ConversationRepository conversations,
                           FeedbackRepository feedbackRepository, RagMetrics metrics) {
        this.metrics = metrics;
        this.messages = messages;
        this.conversations = conversations;
        this.feedbackRepository = feedbackRepository;
    }

    /** Sending feedback again for the same answer replaces the earlier feedback. */
    public FeedbackResponse submit(Caller caller, long messageId, FeedbackRequest request) {
        ChatMessage message = messages.findById(messageId)
                .orElseThrow(() -> new ConversationNotFoundException("Message not found: " + messageId));

        // The answer must belong to one of the caller's conversations
        conversations.findByIdAndTenantIdAndOwnerId(message.getConversationId(), caller.tenantId(), caller.userId())
                .orElseThrow(() -> new ConversationNotFoundException("Message not found: " + messageId));

        if (message.getRole() != MessageRole.ASSISTANT) {
            throw new InvalidRequestException("Feedback can only be given for answers.");
        }

        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().strip();
        Feedback feedback = feedbackRepository.findByMessageId(messageId).orElseGet(() -> new Feedback(messageId));
        feedback.update(request.rating(), comment, request.reportedIncorrect());
        feedbackRepository.save(feedback);
        metrics.feedback(request.rating().name(), request.reportedIncorrect());

        // The comment text is not logged (it may contain personal information)
        log.info("Feedback saved: message={}, rating={}, reportedIncorrect={}",
                messageId, request.rating(), request.reportedIncorrect());
        return new FeedbackResponse(messageId, feedback.getRating(), feedback.getComment(),
                feedback.isReportedIncorrect());
    }
}
