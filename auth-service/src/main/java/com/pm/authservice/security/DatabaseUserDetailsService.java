package com.pm.authservice.security;

import org.springframework.security.core.userdetails.User;
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
                // Return the canonical username so the JWT `sub` is stable regardless of how they logged in.
                .map(user -> User.withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        // roles are stored already-prefixed ('ROLE_ADMIN'), so set them as raw
                        // authorities — .roles(...) would double-prefix to 'ROLE_ROLE_ADMIN'.
                        .authorities(user.getRoles().split(" "))
                        .disabled(!user.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + identifier));
    }
}
