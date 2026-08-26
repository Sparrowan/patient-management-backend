package com.pm.billingservice.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pm.billingservice.model.Payout;

/** Data access for {@link Payout}. The unique {@code idempotencyKey} backs replay detection. */
public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByIdempotencyKey(String idempotencyKey);
}
