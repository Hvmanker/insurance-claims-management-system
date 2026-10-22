package com.hvmanker.insurance.approval.config;

import com.hvmanker.insurance.approval.service.ClaimAlreadyDecidedException;
import com.hvmanker.insurance.common.events.ManualClaimDecisionEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class ApprovalKafkaListenerConfig {
    private static final Logger log = LoggerFactory.getLogger(ApprovalKafkaListenerConfig.class);
    private static final long MANUAL_DECISION_BACKOFF_MS = 2_000L;
    private static final long MANUAL_DECISION_MAX_ATTEMPTS = 15L;

    @Bean
    ConsumerFactory<String, ManualClaimDecisionEvent> manualDecisionConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> consumerProperties = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        consumerProperties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.hvmanker.insurance.common.events");
        consumerProperties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ManualClaimDecisionEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(consumerProperties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ManualClaimDecisionEvent> manualDecisionKafkaListenerContainerFactory(
            ConsumerFactory<String, ManualClaimDecisionEvent> manualDecisionConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, ManualClaimDecisionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(manualDecisionConsumerFactory);
        factory.setCommonErrorHandler(manualDecisionKafkaErrorHandler());
        return factory;
    }

    private CommonErrorHandler manualDecisionKafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler((ConsumerRecord<?, ?> record, Exception exception) -> {
            if (record == null || !(record.value() instanceof ManualClaimDecisionEvent event)) {
                return;
            }
            log.error("manual decision dropped claimId={} alert=true", event.claimId());
        }, new FixedBackOff(MANUAL_DECISION_BACKOFF_MS, MANUAL_DECISION_MAX_ATTEMPTS));
        handler.addNotRetryableExceptions(ClaimAlreadyDecidedException.class);
        return handler;
    }
}
