package com.pm.patientservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;

/**
 * Patient aggregate. A rich domain model: state changes go through intention-revealing
 * behavior ({@link #register}, {@link #updateDetails}), never public setters, so the entity
 * owns its own invariants. {@code registeredDate} in particular is stamped at registration and
 * cannot be set or backdated by callers.
 *
 * <p>Lombok generates getters only — deliberately no {@code @Setter}/{@code @Data}, which would
 * reintroduce setters (breaking encapsulation) and, via toString/equals, touch state in ways
 * that misbehave on JPA entities.
 */
@Entity
@Table(name = "patients")
@Getter
public class Patient {

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

    /** Optimistic-lock version. Managed by JPA; bumped on every update to detect lost updates. */
    @Version
    private long version;

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
