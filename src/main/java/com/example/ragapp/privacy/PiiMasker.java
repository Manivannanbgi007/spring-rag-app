package com.example.ragapp.privacy;

import org.springframework.stereotype.Component;

/**
 * Entry point for masking. Create one session per request:
 *
 *     MaskingSession pii = piiMasker.newSession();
 *     String safeQuestion = pii.mask(question);
 *     ... send safeQuestion to a model ...
 *     String answerForUser = pii.restore(modelAnswer);
 *
 * Why mask when the models run on this computer? The application can be switched to a cloud
 * model (for example OpenAI) by configuration. Then every prompt would leave the company,
 * so the masking is already in place. It can be turned off with app.privacy.mask-pii=false.
 */
@Component
public class PiiMasker {

    private final PrivacyProperties properties;

    public PiiMasker(PrivacyProperties properties) {
        this.properties = properties;
    }

    public MaskingSession newSession() {
        return new MaskingSession(properties.maskPii());
    }
}
