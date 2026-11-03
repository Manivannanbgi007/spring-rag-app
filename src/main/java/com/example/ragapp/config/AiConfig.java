package com.example.ragapp.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Creates the ChatClients: Spring AI's simple entry point for talking to the language model.
 * There are two, because they do different jobs and need different settings.
 */
@Configuration
public class AiConfig {

    /**
     * Answers questions from the documents.
     * The system prompt (the fixed rules for the model) lives in a text file,
     * so it can be improved without touching Java code.
     */
    @Bean
    public ChatClient answerChatClient(ChatClient.Builder builder,
                                       @Value("classpath:prompts/rag-system.st") Resource systemPrompt) {
        return builder.defaultSystem(systemPrompt).build();
    }

    /**
     * Rewrites a follow-up question into a complete question (see QuestionRewriter).
     * Temperature 0 = no creativity, and the output is limited to a short sentence.
     */
    @Bean
    public ChatClient rewriteChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(OllamaChatOptions.builder().temperature(0.0).numPredict(120))
                .build();
    }
}
