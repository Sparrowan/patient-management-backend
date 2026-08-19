package com.pm.patientservice.service;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.grpc.BillingGrpcClient;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.outbox.PatientDeletedPayload;
import com.pm.patientservice.outbox.PatientRegisteredPayload;
import com.pm.patientservice.repository.OutboxEventRepository;
import com.pm.patientservice.repository.PatientRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.AuditorAware;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PatientServiceImpl implements PatientService {

    /** Currency for a new patient's billing account until per-patient currency exists. */
    private static final String DEFAULT_CURRENCY = "USD";

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final BillingGrpcClient billingGrpcClient;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<PatientResponseDTO> getPatients(Pageable pageable) {
        return PagedResponse.from(patientRepository.findAll(pageable).map(patientMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PatientResponseDTO getPatient(UUID id) {
        return patientMapper.toResponse(findByIdOrThrow(id));
    }

    @Override
    @Transactional
    public PatientResponseDTO createPatient(PatientRequestDTO request) {
        if (patientRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        Patient patient = Patient.register(
                request.name(), request.email(), request.address(), request.dateOfBirth());
        Patient saved = patientRepository.save(patient);
        // Transactional outbox: record the "open a billing account" intent in the SAME transaction
        // as the patient insert. The OutboxRelay ships it to Kafka after commit — guaranteed
        // delivery, unlike the old best-effort gRPC call it replaces.
        outboxRepository.save(
                OutboxEvent.forPatientRegistered(saved.getId(), registeredPayload(saved.getId())));
        return patientMapper.toResponse(saved);
    }

    /** Serializes the {@code PatientRegistered} payload stored in the outbox row (ids only, no PHI). */
    private String registeredPayload(UUID patientId) {
        PatientRegisteredPayload payload = new PatientRegisteredPayload(
                UUID.randomUUID().toString(), patientId.toString(), DEFAULT_CURRENCY,
                Instant.now().toEpochMilli(), currentActor());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // A record of String/long fields cannot realistically fail to serialize.
            throw new IllegalStateException("Failed to serialize PatientRegistered payload", e);
        }
    }

    /**
     * The staff user registering the patient — captured now (inside the request, principal present)
     * and carried on the event so billing audits the account it later opens as this user, not
     * {@code "system"}. Reuses the same resolution as JPA auditing (JWT sub, else {@code "system"}).
     */
    private String currentActor() {
        return auditorAware.getCurrentAuditor().orElse("system");
    }

    /** Serializes the {@code PatientDeleted} payload stored in the outbox row (ids only, no PHI). */
    private String deletedPayload(UUID patientId) {
        PatientDeletedPayload payload = new PatientDeletedPayload(
                UUID.randomUUID().toString(), patientId.toString(), Instant.now().toEpochMilli());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PatientDeleted payload", e);
        }
    }

    @Override
    @Transactional
    public PatientResponseDTO updatePatient(UUID id, PatientUpdateRequestDTO request) {
        Patient patient = findByIdOrThrow(id);
        // Optimistic concurrency: reject if the client edited a now-stale version. Hibernate's
        // @Version also guards the tighter window between this load and the flush.
        if (patient.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Patient.class, id);
        }
        if (patientRepository.existsByEmailAndIdNot(request.email(), id)) {
            throw new EmailAlreadyExistsException(request.email());
        }
        patient.updateDetails(
                request.name(), request.email(), request.address(), request.dateOfBirth());
        // saveAndFlush forces the UPDATE (and the @Version increment) now, so the response
        // returns the new version instead of the pre-increment one flushed at commit.
        return patientMapper.toResponse(patientRepository.saveAndFlush(patient));
    }

    @Override
    @Transactional
    public void deletePatient(UUID id) {
        Patient patient = findByIdOrThrow(id); // 404 if unknown/already deleted
        // Synchronous veto (mTLS gRPC): billing closes an empty account, or rejects → 409 if it
        // still holds funds, or 503 if it's unreachable. Runs BEFORE the soft-delete, so a rejection
        // leaves the patient untouched — an immediate, correct answer with no delete/restore flip-flop.
        billingGrpcClient.closeAccountForPatient(id);
        patient.markDeleted();
        patientRepository.save(patient);
        // Fan-out only: announce the deletion for other consumers (analytics/audit/future). Billing
        // already closed the account synchronously above, so for billing this event is a no-op.
        outboxRepository.save(OutboxEvent.forPatientDeleted(id, deletedPayload(id)));
    }

    private Patient findByIdOrThrow(UUID id) {
        return patientRepository.findById(id).orElseThrow(() -> new PatientNotFoundException(id));
    }
}
