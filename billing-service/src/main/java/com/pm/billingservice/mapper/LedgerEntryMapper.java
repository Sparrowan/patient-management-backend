package com.pm.billingservice.mapper;

import com.pm.billingservice.dto.LedgerEntryResponseDTO;
import com.pm.billingservice.model.LedgerRecord;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Ledger movement -&gt; DTO mapping.
 *
 * <p>Maps from {@link LedgerRecord}, not from {@code LedgerEntry}: a movement reads the same to a
 * client whether it is still hot or has been archived, so one mapping serves both halves and the
 * response cannot drift between them.
 */
@Mapper
public interface LedgerEntryMapper {

    LedgerEntryResponseDTO toResponse(LedgerRecord entry);

    List<LedgerEntryResponseDTO> toResponses(List<LedgerRecord> entries);
}
