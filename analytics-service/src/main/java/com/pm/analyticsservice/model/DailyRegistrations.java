package com.pm.analyticsservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;

/**
 * Read model: the number of patient registrations on one calendar day.
 *
 * <p>Unlike a write-side aggregate this carries <b>no {@code BaseEntity} ceremony</b> — no
 * {@code @Version} optimistic lock, no auditing, no soft delete. A projection is a disposable,
 * rebuildable view of the event stream, updated by a single consumer, so that machinery would be
 * dead weight. The date is the natural primary key (one bucket per day). The only behavior is
 * folding registrations in; the projector loads the day's bucket (or {@link #startOn starts} one)
 * and calls {@link #recordOne()}.
 */
@Entity
@Table(name = "daily_registrations")
@Getter
public class DailyRegistrations {

    @Id
    @Column(name = "registration_date")
    private LocalDate registrationDate;

    @Column(nullable = false)
    private long registrations;

    protected DailyRegistrations() {
    }

    private DailyRegistrations(LocalDate registrationDate) {
        this.registrationDate = registrationDate;
        this.registrations = 0;
    }

    /** Starts a fresh bucket for a day at zero. */
    public static DailyRegistrations startOn(LocalDate registrationDate) {
        return new DailyRegistrations(registrationDate);
    }

    /** Folds one registration into this day's total. */
    public void recordOne() {
        this.registrations++;
    }
}
