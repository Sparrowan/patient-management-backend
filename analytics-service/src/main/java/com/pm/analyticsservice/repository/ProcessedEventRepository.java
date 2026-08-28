package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** The consumer's idempotency ledger — {@code existsById(eventId)} answers "already applied?". */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
