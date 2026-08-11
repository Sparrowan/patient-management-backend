package com.pm.billingservice.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

/**
 * Consumer resilience for the {@code patient-events} listener: retry a few times, then route
 * the record to a dead-letter topic ({@code patient-events.DLT}) instead of blocking the
 * partition forever on a poison message. Boot wires this {@link DefaultErrorHandler} into the
 * listener container automatically (it's the single {@code CommonErrorHandler} bean).
 *
 * <p>Two DLT producers because a failed record's value has one of two shapes:
 * <ul>
 *   <li>a valid Avro object — a <b>processing</b> failure (e.g. {@code openAccount} threw) →
 *       {@link KafkaAvroSerializer};</li>
 *   <li>raw bytes — a <b>deserialization</b> failure (schema/poison) → {@link ByteArraySerializer}.</li>
 * </ul>
 * {@link DeadLetterPublishingRecoverer} routes to the right template by the value's runtime type.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> avroDltTemplate,
            KafkaTemplate<String, byte[]> bytesDltTemplate) {
        Map<Class<?>, KafkaOperations<?, ?>> templates = new HashMap<>();
        templates.put(byte[].class, bytesDltTemplate); // deserialization failures (raw bytes)
        templates.put(Object.class, avroDltTemplate);  // processing failures (Avro object)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates);

        // 3 attempts total (2 retries), 1s apart, then publish to <topic>.DLT.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        // A poison/incompatible payload will never deserialize — don't waste retries; DLQ at once.
        handler.addNotRetryableExceptions(DeserializationException.class);
        return handler;
    }

    @Bean
    public KafkaTemplate<String, Object> avroDltTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public KafkaTemplate<String, byte[]> bytesDltTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
