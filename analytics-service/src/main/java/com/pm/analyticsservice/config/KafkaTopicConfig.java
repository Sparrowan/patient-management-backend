package com.pm.analyticsservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the dead-letter topic this service publishes to.
 *
 * <p><b>Why a consumer declares a topic at all.</b> This service does not own {@code patient-events}
 * — patient-service does — but it <em>is</em> the producer of {@code patient-events.DLT}, so the DLT
 * is its to provision.
 *
 * <p><b>And why the partition count must match the source topic.</b> {@code
 * DeadLetterPublishingRecoverer}'s default destination resolver is
 * {@code new TopicPartition(record.topic() + ".DLT", record.partition())} — it <b>preserves the
 * original partition number</b>. Leave the DLT auto-created at the broker default of one partition
 * while {@code patient-events} has three, and a record that fails on partition 1 or 2 is published
 * to a partition that does not exist: the dead-letter send itself fails, so the record is neither
 * processed nor parked. The failure mode is invisible until a poison message happens to land on a
 * high-numbered partition. Keeping the counts equal also means a key's dead-lettered records stay in
 * the same relative order as the originals.
 *
 * <p>The count is duplicated rather than shared, for the same reason {@code billing.proto} and the
 * {@code .avsc} files are copied per service: no shared module, so services stay independently
 * buildable. The coupling is real, so it is stated here rather than left to be discovered — if
 * {@code patient-events} is ever re-sized, this must move with it.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String PATIENT_EVENTS_DLT = "patient-events.DLT";

    /** Must equal {@code patient-events}' partition count — see the class javadoc. */
    static final int PARTITIONS = 3;

    @Value("${kafka.topic.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public NewTopic patientEventsDltTopic() {
        return TopicBuilder.name(PATIENT_EVENTS_DLT)
                .partitions(PARTITIONS)
                .replicas(replicationFactor)
                .build();
    }
}
