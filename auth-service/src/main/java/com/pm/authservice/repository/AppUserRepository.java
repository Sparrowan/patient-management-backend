package com.pm.authservice.repository;

import com.pm.authservice.model.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link AppUser}. {@code findByUsernameOrEmail} backs authentication. */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Resolves a login identifier that may be either a username or an email. Call with the same
     * value for both args: {@code findByUsernameOrEmail(id, id)}. */
    Optional<AppUser> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
