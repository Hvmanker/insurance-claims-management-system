package com.hvmanker.insurance.claims.config;

import com.hvmanker.insurance.claims.domain.PortalUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.flyway.enabled=false"
})
class DemoProfileSeederWithoutDemoProfileTest {

    @Autowired(required = false)
    private DemoProfileSeeder demoProfileSeeder;

    @Autowired
    private PortalUserRepository users;

    @Test
    void portalUsersStayEmptyWhenDemoProfileIsInactive() {
        assertNull(demoProfileSeeder);
        assertEquals(0, users.count());
    }
}
