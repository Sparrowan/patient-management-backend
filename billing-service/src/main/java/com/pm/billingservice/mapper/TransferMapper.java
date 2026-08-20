package com.pm.billingservice.mapper;

import org.mapstruct.Mapper;

import com.pm.billingservice.dto.TransferResponseDTO;
import com.pm.billingservice.model.Transfer;

/** Entity -&gt; DTO mapping for transfers. */
@Mapper
public interface TransferMapper {

    TransferResponseDTO toResponse(Transfer transfer);
}
