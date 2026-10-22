package com.hvmanker.insurance.approval.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class ApprovalDecisionRaceTestConfig {
    @Bean
    @Primary
    LatchingDecisionRaceHook latchingDecisionRaceHook() {
        return new LatchingDecisionRaceHook();
    }
}
