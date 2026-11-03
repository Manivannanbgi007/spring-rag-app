package com.example.ragapp.chat;

import java.util.Map;

import com.example.ragapp.privacy.MaskingSession;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Builds the text that is sent to the language model: the template (prompts/rag-user.st)
 * filled with the context and the question. Sensitive data is masked here.
 */
@Component
public class PromptAssembler {

    private final PromptTemplate template;

    public PromptAssembler(@Value("classpath:prompts/rag-user.st") Resource userPrompt) {
        this.template = new PromptTemplate(userPrompt);
    }

    /**
     * @param maskedQuestion the question, already masked with the same session
     */
    public String assemble(ContextBuilder.BuiltContext context, String maskedQuestion, MaskingSession pii) {
        return template.render(Map.of(
                "context", pii.mask(context.text()),
                "question", maskedQuestion));
    }
}
