package com.example.ragapp.conversation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.conversations" section of application.yml.
 *
 * @param sessionTimeout  a conversation idle for longer than this cannot be continued
 * @param retentionDays   conversations idle for longer than this are deleted for good
 * @param historyMessages how many recent messages are used to understand a follow-up question
 */
@ConfigurationProperties(prefix = "app.conversations")
public record ConversationProperties(Duration sessionTimeout, int retentionDays, int historyMessages) {
}
