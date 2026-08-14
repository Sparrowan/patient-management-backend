package com.pm.authservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.pm.authservice.model.AppUser;
import com.pm.authservice.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Dev seeder (Spring's answer to a Laravel {@code DatabaseSeeder}): creates a default admin so
 * login works on a fresh database. Idempotent — only runs when the table is empty. In production
 * you'd disable seeding and provision the first admin out-of-band.
 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (users.count() == 0) {
            users.save(AppUser.create(
                    "admin", "admin@auth.local", passwordEncoder.encode("password"), "ROLE_ADMIN"));
            log.info("Seeded default admin user (username=admin) — change/remove this outside dev");
        }
    }
}
