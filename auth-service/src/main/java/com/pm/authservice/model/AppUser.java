package com.pm.authservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

/**
 * An application user. Rich model: created via {@link #create}, no public setters — the password is
 * <b>only ever stored as a BCrypt hash</b> (the caller hashes before constructing). Roles are a
 * space-separated authority list, e.g. {@code "ROLE_ADMIN"}. Audit fields + version from
 * {@link BaseEntity}; {@code @Getter} only (never {@code @Setter}/{@code @Data} on a JPA entity).
 */
@Entity
@Table(name = "users")
@Getter
public class AppUser extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 255)
    private String roles;

    @Column(nullable = false)
    private boolean enabled;

    /** Required by JPA. Use {@link #create}. */
    protected AppUser() {
    }

    private AppUser(String username, String email, String passwordHash, String roles) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.enabled = true;
    }

    /** Creates an enabled user. {@code passwordHash} must already be BCrypt-hashed by the caller. */
    public static AppUser create(String username, String email, String passwordHash, String roles) {
        return new AppUser(username, email, passwordHash, roles);
    }
}
