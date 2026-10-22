package com.hvmanker.insurance.approval.config;

import com.hvmanker.insurance.common.events.ManualClaimDecisionEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class ApprovalKafkaListenerConfig {
    private static final Logger log = LoggerFactory.getLogger(ApprovalKafkaListenerConfig.class);

    @Bean
    FixedBackOff manualDecisionBackOff() {
        return new FixedBackOff(2_000L, 14L);
    }

    @Bean
    ConsumerRecordRecoverer manualDecisionRecoverer() {
        return (ConsumerRecord<?, ?> record, Exception exception) -> {
            if (record == null || !(record.value() instanceof ManualClaimDecisionEvent event)) {
                return;
            }
            log.error("manual decision dropped claimId={} alert=true", event.claimId());
        };
    }

    @Bean
    DefaultErrorHandler manualDecisionErrorHandler(
            FixedBackOff manualDecisionBackOff,
            ConsumerRecordRecoverer manualDecisionRecoverer) {
        return new DefaultErrorHandler(manualDecisionRecoverer, manualDecisionBackOff);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ManualClaimDecisionEvent> manualDecisionKafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            DefaultErrorHandler manualDecisionErrorHandler) {
        Map<String, Object> consumerProperties = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        consumerProperties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.hvmanker.insurance.common.events");
        consumerProperties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ManualClaimDecisionEvent.class.getName());
        ConcurrentKafkaListenerContainerFactory<String, ManualClaimDecisionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerProperties));
        factory.setCommonErrorHandler(manualDecisionErrorHandler);
        return factory;
    }
}
