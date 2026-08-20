package com.pm.billingservice.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pm.billingservice.model.Transfer;

/** Data access for {@link Transfer}. The unique {@code idempotencyKey} backs replay detection. */
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}
