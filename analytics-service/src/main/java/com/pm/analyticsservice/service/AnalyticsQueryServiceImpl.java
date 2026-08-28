package com.pm.analyticsservice.service;

import com.pm.analyticsservice.dto.DailyRegistrationView;
import com.pm.analyticsservice.dto.RegistrationSummaryView;
import com.pm.analyticsservice.repository.ActivePatientRepository;
import com.pm.analyticsservice.repository.DailyRegistrationsRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin read-side service: the repository already returns the response shape, so this just enforces
 * the read-only transaction boundary and keeps the controller depending on an interface, not the
 * repository. There is no business logic here — that's the point of a projection: the work was done
 * up front by the {@code RegistrationProjector}, so reads are cheap and dumb.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsQueryServiceImpl implements AnalyticsQueryService {

    private final DailyRegistrationsRepository repository;
    private final ActivePatientRepository activePatients;

    @Override
    @Transactional(readOnly = true)
    public List<DailyRegistrationView> registrationsBetween(LocalDate from, LocalDate to) {
        return repository.findRange(from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationSummaryView summary() {
        return repository.summarize();
    }

    @Override
    @Transactional(readOnly = true)
    public long activePatientCount() {
        return activePatients.count();
    }
}
