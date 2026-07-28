package com.pm.patientservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.PatientUpdateRequestDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.PatientRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the service layer in isolation — repository and mapper are mocked, so these
 * assert business rules and control flow only (no Spring context, no database).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientServiceImpl")
class PatientServiceImplTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate DOB = LocalDate.of(1990, 5, 10);

    @Mock private PatientRepository patientRepository;
    @Mock private PatientMapper patientMapper;
    @InjectMocks private PatientServiceImpl patientService;

    private Patient existingPatient() {
        Patient patient = Patient.register("Ada Lovelace", "ada@example.com", "12 Analytical St", DOB);
        ReflectionTestUtils.setField(patient, "id", ID);
        return patient;
    }

    private PatientResponseDTO responseDto() {
        return new PatientResponseDTO(ID, "Ada Lovelace", "ada@example.com", "12 Analytical St", DOB, 0L);
    }

    @Nested
    @DisplayName("getPatients")
    class GetPatients {

        @Test
        @DisplayName("maps the page and reports totals")
        void returnsPagedResponse() {
            Patient patient = existingPatient();
            Page<Patient> page = new PageImpl<>(List.of(patient), PageRequest.of(0, 20), 1);
            when(patientRepository.findAll(any(Pageable.class))).thenReturn(page);
            when(patientMapper.toResponse(patient)).thenReturn(responseDto());

            PagedResponse<PatientResponseDTO> result = patientService.getPatients(PageRequest.of(0, 20));

            assertThat(result.content()).containsExactly(responseDto());
            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.last()).isTrue();
        }
    }

    @Nested
    @DisplayName("getPatient")
    class GetPatient {

        @Test
        @DisplayName("returns the patient when it exists")
        void returnsPatient() {
            Patient patient = existingPatient();
            when(patientRepository.findById(ID)).thenReturn(Optional.of(patient));
            when(patientMapper.toResponse(patient)).thenReturn(responseDto());

            assertThat(patientService.getPatient(ID)).isEqualTo(responseDto());
        }

        @Test
        @DisplayName("throws PatientNotFoundException when absent")
        void throwsWhenMissing() {
            when(patientRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> patientService.getPatient(ID))
                    .isInstanceOf(PatientNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createPatient")
    class CreatePatient {

        private final PatientRequestDTO request =
                new PatientRequestDTO("Ada Lovelace", "ada@example.com", "12 Analytical St", DOB);

        @Test
        @DisplayName("registers a patient and stamps the registration date server-side")
        void createsPatient() {
            when(patientRepository.existsByEmail("ada@example.com")).thenReturn(false);
            when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
            when(patientMapper.toResponse(any(Patient.class))).thenReturn(responseDto());

            PatientResponseDTO result = patientService.createPatient(request);

            assertThat(result).isEqualTo(responseDto());
            ArgumentCaptor<Patient> saved = ArgumentCaptor.forClass(Patient.class);
            verify(patientRepository).save(saved.capture());
            assertThat(saved.getValue().getRegisteredDate()).isNotNull();
            assertThat(saved.getValue().getName()).isEqualTo("Ada Lovelace");
        }

        @Test
        @DisplayName("rejects a duplicate email and never saves")
        void rejectsDuplicateEmail() {
            when(patientRepository.existsByEmail("ada@example.com")).thenReturn(true);

            assertThatThrownBy(() -> patientService.createPatient(request))
                    .isInstanceOf(EmailAlreadyExistsException.class);
            verify(patientRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updatePatient")
    class UpdatePatient {

        private PatientUpdateRequestDTO request(long version) {
            return new PatientUpdateRequestDTO("Ada L.", "ada@example.com", "New Address", DOB, version);
        }

        @Test
        @DisplayName("updates details when the version matches")
        void updatesWhenVersionMatches() {
            Patient patient = existingPatient(); // version 0
            when(patientRepository.findById(ID)).thenReturn(Optional.of(patient));
            when(patientRepository.existsByEmailAndIdNot("ada@example.com", ID)).thenReturn(false);
            when(patientRepository.saveAndFlush(patient)).thenReturn(patient);
            when(patientMapper.toResponse(patient)).thenReturn(responseDto());

            PatientResponseDTO result = patientService.updatePatient(ID, request(0L));

            assertThat(result).isEqualTo(responseDto());
            assertThat(patient.getAddress()).isEqualTo("New Address");
            verify(patientRepository).saveAndFlush(patient);
        }

        @Test
        @DisplayName("throws optimistic-lock failure on a stale version and never saves")
        void rejectsStaleVersion() {
            Patient patient = existingPatient(); // version 0
            when(patientRepository.findById(ID)).thenReturn(Optional.of(patient));

            assertThatThrownBy(() -> patientService.updatePatient(ID, request(5L)))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            verify(patientRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("throws PatientNotFoundException when absent")
        void throwsWhenMissing() {
            when(patientRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> patientService.updatePatient(ID, request(0L)))
                    .isInstanceOf(PatientNotFoundException.class);
        }

        @Test
        @DisplayName("rejects an email already used by another patient")
        void rejectsDuplicateEmail() {
            Patient patient = existingPatient();
            when(patientRepository.findById(ID)).thenReturn(Optional.of(patient));
            when(patientRepository.existsByEmailAndIdNot("ada@example.com", ID)).thenReturn(true);

            assertThatThrownBy(() -> patientService.updatePatient(ID, request(0L)))
                    .isInstanceOf(EmailAlreadyExistsException.class);
            verify(patientRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("deletePatient")
    class DeletePatient {

        @Test
        @DisplayName("soft-deletes (marks deleted + stamps deletedAt) when the patient exists")
        void deletesWhenPresent() {
            Patient patient = existingPatient();
            when(patientRepository.findById(ID)).thenReturn(Optional.of(patient));
            when(patientRepository.save(patient)).thenReturn(patient);

            patientService.deletePatient(ID);

            assertThat(patient.isDeleted()).isTrue();
            assertThat(patient.getDeletedAt()).isNotNull();
            verify(patientRepository).save(patient);
        }

        @Test
        @DisplayName("throws PatientNotFoundException and never saves when absent")
        void throwsWhenMissing() {
            when(patientRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> patientService.deletePatient(ID))
                    .isInstanceOf(PatientNotFoundException.class);
            verify(patientRepository, never()).save(any());
        }
    }
}
