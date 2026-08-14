package com.pm.authservice.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pm.authservice.dto.RegisterRequest;
import com.pm.authservice.dto.UserResponse;
import com.pm.authservice.exception.EmailAlreadyExistsException;
import com.pm.authservice.exception.UsernameAlreadyExistsException;
import com.pm.authservice.model.AppUser;
import com.pm.authservice.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

/** Registration. Hashes the password (never stores plaintext) and defaults new users to
 * {@code ROLE_USER}. The unique constraints are the real guard; the {@code existsBy} checks just
 * turn the common case into a friendly 409 instead of a DB error. */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String DEFAULT_ROLE = "ROLE_USER";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (users.existsByUsername(request.username())) {
            throw new UsernameAlreadyExistsException(request.username());
        }
        if (users.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        AppUser user = AppUser.create(
                request.username(), request.email(), passwordEncoder.encode(request.password()), DEFAULT_ROLE);
        AppUser saved = users.save(user);
        return new UserResponse(saved.getId(), saved.getUsername(), saved.getEmail(), saved.getRoles());
    }
}
