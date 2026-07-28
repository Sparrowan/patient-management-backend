package com.pm.billingservice.repository;

import com.pm.billingservice.model.BillingAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link BillingAccount}. One account per patient (unique patientId). */
public interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {

    boolean existsByPatientId(UUID patientId);

    Optional<BillingAccount> findByPatientId(UUID patientId);
}
