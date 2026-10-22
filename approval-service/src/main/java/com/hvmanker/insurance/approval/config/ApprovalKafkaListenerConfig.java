package com.hvmanker.insurance.approval.config;

import com.hvmanker.insurance.approval.service.ClaimAlreadyDecidedException;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class ApprovalKafkaListenerConfig {

    @Bean
    CommonErrorHandler approvalKafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(ClaimAlreadyDecidedException.class);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactoryCustomizer approvalKafkaListenerContainerFactoryCustomizer(
            CommonErrorHandler approvalKafkaErrorHandler) {
        return factory -> factory.setCommonErrorHandler(approvalKafkaErrorHandler);
    }
}
