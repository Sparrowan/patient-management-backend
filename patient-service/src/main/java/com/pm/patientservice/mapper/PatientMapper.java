package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity -&gt; DTO mapping for patients. MapStruct generates the implementation at compile time
 * (no reflection) and registers it as a Spring bean (componentModel=spring, set globally in the
 * pom). Adding an unmapped target field fails the build (unmappedTargetPolicy=ERROR).
 *
 * <p>There is intentionally no {@code toEntity}: creation goes through the {@link Patient#register}
 * domain factory, not a mapper populating the entity via setters.
 */
@Mapper
public interface PatientMapper {

    PatientResponseDTO toResponse(Patient patient);

    List<PatientResponseDTO> toResponses(List<Patient> patients);
}
