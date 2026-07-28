package com.pm.billingservice.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope for list endpoints. Wrapping {@link Page} in an explicit record
 * avoids serializing Spring's {@code PageImpl} directly (whose JSON shape is unstable).
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
