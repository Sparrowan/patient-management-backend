package com.pm.patientservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@code patient-events} topic, which patient-service owns as its producer.
 *
 * <p><b>Why this class exists.</b> Without a {@link NewTopic} bean the broker auto-creates the topic
 * on first publish using its own {@code num.partitions} default — <b>one partition</b>. That silently
 * caps consumer parallelism: a consumer group can have at most one member per partition, so
 * <em>however many</em> replicas of billing-service or analytics-service run, exactly one does the
 * work and the rest idle. Nothing warns you, because a single instance behaves perfectly; the defect
 * only surfaces at the moment you scale out to fix a throughput problem. Declaring the topic here
 * puts the number in source control instead of in whatever the broker happened to default to.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String PATIENT_EVENTS_TOPIC = "patient-events";

    /**
     * Partition count — the ceiling on how many consumers in one group can work in parallel.
     *
     * <p><b>Deliberately a constant, not a property.</b> It looks like a tuning knob but it is a
     * topology decision: the partition a record lands on is {@code hash(key) % partitions}, so
     * changing the count changes that mapping and therefore the ordering guarantees. Dev should
     * exercise the same mapping as production, so this must <em>not</em> drift per environment.
     *
     * <p>Three, for the usual reason: it needs to be at least the number of consumers you expect in
     * the hungriest group, with a little headroom, and no more — every partition is more files, more
     * broker memory and more controller metadata. Two groups consume this stream today (billing,
     * analytics), each realistically 1–3 instances.
     *
     * <p><b>Two one-way doors.</b> Partitions can be added but never removed. And adding them
     * re-maps existing keys, so for a brief window one aggregate's events can be split across an old
     * and a new partition and arrive out of order — size this once, deliberately, rather than
     * creeping it upward later.
     */
    static final int PARTITIONS = 3;

    /**
     * Replication factor <em>is</em> environment-varying: it can never exceed the broker count, and
     * local dev runs a single-broker KRaft node. One means <b>no durability</b> — losing that broker
     * loses the log. Production runs 3 with {@code min.insync.replicas=2}, which is what makes the
     * producer's {@code acks=all} actually mean something.
     */
    @Value("${kafka.topic.replication-factor:1}")
    private short replicationFactor;

    /**
     * Spring's {@code KafkaAdmin} applies this at start-up: it creates the topic if absent, and
     * <em>increases</em> the partitions of an existing one to match. That is the migration path for
     * the single-partition topic already out there — and the moment the key re-mapping above applies.
     */
    @Bean
    public NewTopic patientEventsTopic() {
        return TopicBuilder.name(PATIENT_EVENTS_TOPIC)
                .partitions(PARTITIONS)
                .replicas(replicationFactor)
                .build();
    }
}
