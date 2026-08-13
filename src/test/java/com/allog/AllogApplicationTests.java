package com.allog;

import com.allog.ai.coaching.controller.AiCoachPreviewController;
import com.allog.ai.coaching.provider.AiCoachProvider;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "ai.openai.api-key=",
        "ai.coach.model="
})
@ActiveProfiles("test")
class AllogApplicationTests {

    @Autowired
    private AiCoachProvider provider;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertFalse(provider.isAvailable());
        assertTrue(applicationContext.getBeansOfType(AiCoachPreviewController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(Clock.class).size() == 1);
        assertTrue(applicationContext.getBeansOfType(S3Client.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(S3Presigner.class).isEmpty());
        VerificationMediaStorage storage = applicationContext.getBean(VerificationMediaStorage.class);
        assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.inspect("verification-media/test")
        );
    }
}
