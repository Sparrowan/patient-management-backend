package com.pm.billingservice.mapper;

import com.pm.billingservice.dto.BillingAccountResponseDTO;
import com.pm.billingservice.model.BillingAccount;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity -&gt; DTO mapping for billing accounts. MapStruct generates the implementation at compile
 * time and registers it as a Spring bean; adding an unmapped target field fails the build. There
 * is intentionally no {@code toEntity}: creation goes through {@link BillingAccount#openFor}.
 */
@Mapper
public interface BillingAccountMapper {

    BillingAccountResponseDTO toResponse(BillingAccount account);

    List<BillingAccountResponseDTO> toResponses(List<BillingAccount> accounts);
}
