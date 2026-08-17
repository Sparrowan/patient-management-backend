package com.pm.authservice.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.pm.authservice.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Loads users from the database for authentication. Spring's {@code AuthenticationManager} calls
 * this to fetch the stored (hashed) password, then compares it to the submitted one via the
 * {@code PasswordEncoder} — we never compare passwords ourselves. Being the only
 * {@link UserDetailsService} bean, it replaces the in-memory one from bit 1 automatically.
 */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    @Override
    public UserDetails loadUserByUsername(String identifier) {
        // The submitted "username" is really a login identifier — it may be a username or an email.
        return users.findByUsernameOrEmail(identifier, identifier)
                // Carry the stable id (for the token `sub`) alongside the canonical username, so both
                // the identifier and the display name are the same regardless of how they logged in.
                .map(user -> (UserDetails) new AppUserDetails(
                        user.getId(),
                        user.getUsername(),
                        user.getPasswordHash(),
                        user.isEnabled(),
                        // roles are stored already-prefixed ('ROLE_ADMIN'); create authorities as-is —
                        // .roles(...) would double-prefix to 'ROLE_ROLE_ADMIN'.
                        AuthorityUtils.createAuthorityList(user.getRoles().split(" "))))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + identifier));
    }
}
