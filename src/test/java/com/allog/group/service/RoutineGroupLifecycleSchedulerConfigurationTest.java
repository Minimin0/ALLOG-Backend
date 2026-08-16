package com.allog.group.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

class RoutineGroupLifecycleSchedulerConfigurationTest {

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:lifecycle-scheduler-default;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver"
    })
    class WhenUsingDefaultRuntimeConfiguration {

        @Autowired
        private Optional<RoutineGroupLifecycleScheduler> scheduler;

        @Test
        void registersTheLifecycleScheduler() {
            assertTrue(scheduler.isPresent());
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class WhenUsingTestProfile {

        @Autowired
        private Optional<RoutineGroupLifecycleScheduler> scheduler;

        @Test
        void doesNotRegisterTheLifecycleScheduler() {
            assertTrue(scheduler.isEmpty());
        }
    }
}
