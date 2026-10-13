package com.pm.analyticsservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the DLT's partition count to the source topic's.
 *
 * <p>{@code DeadLetterPublishingRecoverer} publishes to the <em>same partition number</em> the
 * record failed on, so a DLT narrower than {@code patient-events} drops dead letters from the
 * high-numbered partitions — the send targets a partition that does not exist. Nothing in the
 * functional suite reaches that path, so this assertion is the only thing standing between the two
 * counts drifting apart and a silently lost poison message.
 */
@DisplayName("patient-events.DLT topic declaration")
class KafkaTopicConfigTest {

    @Test
    @DisplayName("matches the source topic's partition count, or dead letters are dropped")
    void partitionsMatchSourceTopic() {
        KafkaTopicConfig config = new KafkaTopicConfig();
        ReflectionTestUtils.setField(config, "replicationFactor", (short) 1);

        assertThat(config.patientEventsDltTopic().numPartitions())
                .as("DeadLetterPublishingRecoverer reuses the failed record's partition number")
                .isEqualTo(KafkaTopicConfig.PARTITIONS)
                .isGreaterThan(1);
        assertThat(config.patientEventsDltTopic().name()).isEqualTo("patient-events.DLT");
    }
}
