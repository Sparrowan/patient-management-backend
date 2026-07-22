package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity -&gt; DTO mapping for patients. MapStruct generates the implementation at compile time
 * (no reflection) and registers it as a Spring bean (componentModel=spring, set globally in the
 * pom). Fields map by name/type; adding an unmapped field to a DTO fails the build
 * (unmappedTargetPolicy=ERROR).
 */
@Mapper
public interface PatientMapper {

    PatientResponseDTO toResponse(Patient patient);

    List<PatientResponseDTO> toResponses(List<Patient> patients);
}
