package com.pm.patientservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.SQLRestriction;

/**
 * Patient aggregate. A rich domain model: state changes go through intention-revealing
 * behavior ({@link #register}, {@link #updateDetails}), never public setters, so the entity
 * owns its own invariants. {@code registeredDate} is stamped at registration and cannot be
 * backdated by callers. Audit timestamps + version are inherited from {@link BaseEntity}.
 *
 * <p>Soft delete: {@link #markDeleted()} sets {@code deleted}/{@code deletedAt} and the row is
 * kept; {@code @SQLRestriction} filters soft-deleted rows out of every query. The email stays in
 * the {@code UNIQUE} index while soft-deleted (emails are not reusable) — a re-create with that
 * email hits the DB constraint, mapped to 409 by the handler.
 *
 * <p>Lombok generates getters only — never {@code @Setter}/{@code @Data} on a JPA entity.
 */
@Entity
@Table(name = "patients")
@SQLRestriction("deleted_at is null")
@Getter
public class Patient extends SoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Column(nullable = false)
    private LocalDate registeredDate;

    /** Required by JPA. Use {@link #register} to create instances. */
    protected Patient() {
    }

    private Patient(String name, String email, String address, LocalDate dateOfBirth) {
        this.name = name;
        this.email = email;
        this.address = address;
        this.dateOfBirth = dateOfBirth;
        this.registeredDate = LocalDate.now();
    }

    /**
     * Registers a new patient, stamping the registration date. Registration date is an
     * invariant the entity owns — it is set here and never accepted from the client.
     */
    public static Patient register(String name, String email, String address, LocalDate dateOfBirth) {
        return new Patient(name, email, address, dateOfBirth);
    }

    /** Replaces the mutable details. Identity and registration date are unaffected. */
    public void updateDetails(String name, String email, String address, LocalDate dateOfBirth) {
        this.name = name;
        this.email = email;
        this.address = address;
        this.dateOfBirth = dateOfBirth;
    }
}
