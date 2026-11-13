package com.hvmanker.insurance.common.outbox;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;

public final class OutboxInboxSpringHarness {

    private static AnnotationConfigApplicationContext context;

    private OutboxInboxSpringHarness() {
    }

    public static synchronized void resetContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    public static synchronized AnnotationConfigApplicationContext context() {
        if (context == null) {
            OutboxInboxTestSupport.startContainers();
            context = new AnnotationConfigApplicationContext();
            DataSource dataSource = OutboxInboxTestSupport.newDataSource();
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.register(OutboxInboxSpringTestConfig.class);
            context.refresh();
            try {
                OutboxInboxTestSupport.applySchema(context.getBean(JdbcTemplate.class));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return context;
    }

    public static JdbcTemplate jdbcTemplate() {
        return context().getBean(JdbcTemplate.class);
    }
}
