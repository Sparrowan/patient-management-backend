package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.dto.DailyRegistrationView;
import com.pm.analyticsservice.dto.RegistrationSummaryView;
import com.pm.analyticsservice.model.DailyRegistrations;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for the {@link DailyRegistrations} read model. The query methods project straight into
 * the response DTOs via JPQL constructor expressions — on the read side the query result <em>is</em>
 * the wire shape, so there's no entity→DTO mapper step. The heavy lifting (the aggregation) was done
 * once by the projector at write time, so these reads are a keyed range scan, not a GROUP BY.
 */
public interface DailyRegistrationsRepository extends JpaRepository<DailyRegistrations, LocalDate> {

    @Query("""
            select new com.pm.analyticsservice.dto.DailyRegistrationView(d.registrationDate, d.registrations)
            from DailyRegistrations d
            where d.registrationDate between :from and :to
            order by d.registrationDate
            """)
    List<DailyRegistrationView> findRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select new com.pm.analyticsservice.dto.RegistrationSummaryView(
                coalesce(sum(d.registrations), 0), count(d))
            from DailyRegistrations d
            """)
    RegistrationSummaryView summarize();
}
