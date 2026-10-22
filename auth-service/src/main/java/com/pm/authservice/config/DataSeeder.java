package com.pm.authservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.pm.authservice.model.AppUser;
import com.pm.authservice.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Dev seeder: creates a default admin so login works on a fresh database. Idempotent — only runs
 * when the table is empty.
 *
 * <p><b>Opt-in, and deliberately fail-closed.</b> This bean does not exist unless
 * {@code auth.seed-default-admin=true} is set explicitly. It previously ran in <em>every</em>
 * environment, which meant a fresh production database would be created with
 * {@code admin}/{@code password} holding {@code ROLE_ADMIN} — and {@code ROLE_ADMIN} is exactly what
 * gates every money movement (credit, debit, transfer, payout) plus patient deletion. Guessable
 * total control of the money path, on day one.
 *
 * <p><b>Why a property rather than {@code @Profile("!prod")}.</b> A negated profile is fail-<em>open</em>:
 * forget to set {@code SPRING_PROFILES_ACTIVE} in production — or typo it — and the seeder runs.
 * There is no {@code prod} profile in this repo today, so that guard would have protected nothing.
 * Requiring an explicit opt-in inverts the default: the unsafe thing now needs a deliberate act,
 * and the absence of configuration is safe. {@code matchIfMissing} is omitted on purpose, so it
 * defaults to false.
 *
 * <p>The seeded credentials are intentionally <b>not</b> configurable here. Making them
 * configurable would invite someone to "just set a stronger password" and keep using the mechanism
 * in production; a real first admin is provisioned out-of-band, so the seeder stays a dev
 * convenience with a loud warning.
 */
@Component
@ConditionalOnProperty(prefix = "auth", name = "seed-default-admin", havingValue = "true")
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
            // WARN, not INFO: if this line ever appears in a production log it is an incident, and
            // it needs to be visible at the level operators actually alert on.
            log.warn("Seeded default admin (username=admin, password=password, ROLE_ADMIN). "
                    + "DEV ONLY — this account can move money. Never enable auth.seed-default-admin "
                    + "outside local development.");
        }
    }
}
