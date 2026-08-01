package com.pm.patientservice.service;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.event.PatientRegisteredEvent;
import com.pm.patientservice.repository.PatientRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

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
        // Open the billing account after this transaction commits (BillingGrpcClient listens).
        eventPublisher.publishEvent(new PatientRegisteredEvent(saved.getId(), DEFAULT_CURRENCY));
        return patientMapper.toResponse(saved);
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
        // Soft delete: load (already-deleted rows are filtered out → 404), mark, and persist.
        Patient patient = findByIdOrThrow(id);
        patient.markDeleted();
        patientRepository.save(patient);
    }

    private Patient findByIdOrThrow(UUID id) {
        return patientRepository.findById(id).orElseThrow(() -> new PatientNotFoundException(id));
    }
}
