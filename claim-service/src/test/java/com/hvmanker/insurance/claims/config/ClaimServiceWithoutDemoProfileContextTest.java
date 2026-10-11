package com.hvmanker.insurance.claims.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ClaimServiceSpringContextTest
class ClaimServiceWithoutDemoProfileContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void demoProfileSeederBeanIsAbsentWhenDemoProfileIsInactive() {
        assertTrue(applicationContext.getBeansOfType(DemoProfileSeeder.class).isEmpty());
    }
}
