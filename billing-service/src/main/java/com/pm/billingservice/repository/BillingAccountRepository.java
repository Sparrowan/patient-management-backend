package com.pm.billingservice.repository;

import com.pm.billingservice.model.BillingAccount;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/** Data access for {@link BillingAccount}. One account per patient (unique patientId). */
public interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {

    boolean existsByPatientId(UUID patientId);

    Optional<BillingAccount> findByPatientId(UUID patientId);

    /**
     * Loads an account under a pessimistic write lock ({@code SELECT ... FOR UPDATE}) for
     * money movement, so concurrent credits/debits on the same account serialize instead of
     * failing with an optimistic-lock 409. The lock-timeout hint bounds the wait (MariaDB
     * ultimately caps it via {@code innodb_lock_wait_timeout}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select a from BillingAccount a where a.id = :id")
    Optional<BillingAccount> findByIdForUpdate(UUID id);
}
