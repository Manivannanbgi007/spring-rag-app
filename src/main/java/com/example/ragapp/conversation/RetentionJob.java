package com.example.ragapp.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention policy: once a night, conversations that have been idle for more than
 * "app.conversations.retention-days" are deleted for good (with messages and feedback).
 * Keeping personal data forever is a privacy risk, so old data is removed automatically.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final ConversationService conversationService;

    public RetentionJob(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Scheduled(cron = "${app.conversations.retention-cron}")
    public void deleteOldConversations() {
        int deleted = conversationService.deleteIdleConversations();
        log.info("Retention job finished: {} old conversation(s) deleted", deleted);
    }
}
