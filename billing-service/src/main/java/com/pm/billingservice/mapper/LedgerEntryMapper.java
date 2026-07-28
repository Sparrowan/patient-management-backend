package com.pm.billingservice.mapper;

import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.model.LedgerEntry;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity -&gt; DTO mapping for ledger entries. {@code createdAt} maps from {@code BaseEntity}. */
@Mapper
public interface LedgerEntryMapper {

    LedgerEntryResponseDTO toResponse(LedgerEntry entry);

    List<LedgerEntryResponseDTO> toResponses(List<LedgerEntry> entries);
}
