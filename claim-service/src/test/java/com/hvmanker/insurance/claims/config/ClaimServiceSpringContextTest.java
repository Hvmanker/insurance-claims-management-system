package com.hvmanker.insurance.claims.config;

import com.hvmanker.insurance.claims.ClaimServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = ClaimServiceApplication.class)
@EmbeddedKafka(partitions = 1, topics = {
        "claim.submitted",
        "claim.manual-decision",
        "claim.approved",
        "claim.rejected",
        "claim.settled"
})
@TestPropertySource(locations = "classpath:application-claim-service-spring-context-test.properties")
public @interface ClaimServiceSpringContextTest {
}
