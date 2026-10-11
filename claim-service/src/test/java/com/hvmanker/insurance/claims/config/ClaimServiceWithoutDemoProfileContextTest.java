package com.hvmanker.insurance.claims.config;

import com.hvmanker.insurance.claims.ClaimServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = ClaimServiceApplication.class)
@EmbeddedKafka(partitions = 1, topics = {
        "claim.submitted",
        "claim.manual-decision",
        "claim.approved",
        "claim.rejected",
        "claim.settled"
})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:claim-no-demo-context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.flyway.enabled=false",
        "spring.cache.type=simple",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
class ClaimServiceWithoutDemoProfileContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private DemoProfileSeeder demoProfileSeeder;

    @Test
    void demoProfileSeederBeanIsAbsentWhenDemoProfileIsInactive() {
        assertTrue(applicationContext.getBeansOfType(DemoProfileSeeder.class).isEmpty());
        assertNull(demoProfileSeeder);
    }
}
