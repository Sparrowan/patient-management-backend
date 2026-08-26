package com.pm.billingservice.mapper;

import org.mapstruct.Mapper;

import com.pm.billingservice.dto.PayoutResponseDTO;
import com.pm.billingservice.model.Payout;

/** Entity -&gt; DTO mapping for payouts. */
@Mapper
public interface PayoutMapper {

    PayoutResponseDTO toResponse(Payout payout);
}
