package com.pm.patientservice.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;

/**
 * Producer for the {@code patient-events} stream (used by the outbox relay). Defined explicitly —
 * rather than via Boot's auto-configured {@code KafkaTemplate} — because the consumer config also
 * declares {@code KafkaTemplate} beans (for the DLQ), which makes Boot back off its default one.
 * Carries {@code TopicRecordNameStrategy} so the topic can hold multiple event types.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Bean
    public KafkaTemplate<String, Object> patientEventsKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY, TopicRecordNameStrategy.class.getName());
        // acks=all: the broker only acks once the record is fully replicated — no silent loss.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        // Create a producer span per send and inject the W3C traceparent into the record headers, so
        // the consumer can continue the trace. The auto-config property (spring.kafka.template.
        // observation-enabled) only touches Boot's template; this bean is hand-built, so we set it
        // here. The ObservationRegistry is picked up from the context (this is a Spring-managed bean).
        template.setObservationEnabled(true);
        return template;
    }
}
