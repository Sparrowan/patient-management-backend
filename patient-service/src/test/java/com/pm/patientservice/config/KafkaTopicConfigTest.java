package com.pm.patientservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Guards the topic declaration. These assertions look trivial, and that is the point: the values
 * they pin are the ones whose absence is silent. A topic that reverts to the broker's one-partition
 * default still passes every functional test in the suite, because one consumer instance works
 * perfectly — so only an explicit assertion catches it.
 */
@DisplayName("patient-events topic declaration")
class KafkaTopicConfigTest {

    private NewTopic topic(short replicationFactor) {
        KafkaTopicConfig config = new KafkaTopicConfig();
        ReflectionTestUtils.setField(config, "replicationFactor", replicationFactor);
        return config.patientEventsTopic();
    }

    @Test
    @DisplayName("declares more than one partition — the whole point of declaring it")
    void declaresMultiplePartitions() {
        assertThat(topic((short) 1).numPartitions())
                .as("one partition caps a consumer group at one working member, whatever the replica count")
                .isGreaterThan(1)
                .isEqualTo(KafkaTopicConfig.PARTITIONS);
    }

    @Test
    @DisplayName("names the topic the relay actually publishes to")
    void nameMatchesTheProducer() {
        assertThat(topic((short) 1).name()).isEqualTo("patient-events");
    }

    @Test
    @DisplayName("takes the replication factor from configuration, so prod can exceed dev's single broker")
    void replicationFactorIsConfigurable() {
        assertThat(topic((short) 3).replicationFactor()).isEqualTo((short) 3);
        assertThat(topic((short) 1).replicationFactor()).isEqualTo((short) 1);
    }
}
