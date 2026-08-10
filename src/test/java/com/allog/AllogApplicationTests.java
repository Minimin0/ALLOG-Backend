package com.allog;

import com.allog.ai.coaching.controller.AiCoachPreviewController;
import com.allog.ai.coaching.provider.AiCoachProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }
}
