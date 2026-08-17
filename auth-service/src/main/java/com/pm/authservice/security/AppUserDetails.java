package com.pm.authservice.security;

import java.util.Collection;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * Spring's built-in {@link User} principal carries only the username. We extend it to also carry the
 * stable {@link #id} (the DB primary key) so {@code TokenService} can put it in the token's
 * {@code sub} claim — an <b>immutable, never-reassigned</b> identifier, unlike a username/email
 * (which can change or be reused). Downstream services persist {@code sub} as the audit "who".
 */
public class AppUserDetails extends User {

    private final UUID id;

    public AppUserDetails(
            UUID id,
            String username,
            String password,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
