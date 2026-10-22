package com.pm.authservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pm.authservice.repository.AppUserRepository;
import org.mockito.Mockito;

/**
 * The two guards that stood between this service and a production incident.
 *
 * <p>Both were documented and unimplemented: {@code DataSeeder}'s javadoc said "in production you'd
 * disable seeding" and nothing disabled it, while {@code JwtKeysConfig}'s said "production loads a
 * persistent key" and nothing loaded one. Comments are not controls, so these are tests.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: each case needs a
 * <em>different</em> property set, and booting the full context per permutation would fork the
 * context cache (and need a database) to assert something that is purely about conditional wiring.
 */
@DisplayName("Production safety guards")
class ProductionSafetyGuardsTest {

    @Nested
    @DisplayName("Default-admin seeding")
    class DefaultAdminSeeding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SeederSupport.class, DataSeederRegistration.class)
                .withConfiguration(AutoConfigurations.of());

        @Test
        @DisplayName("is ABSENT by default — the dangerous option requires a deliberate opt-in")
        void absentByDefault() {
            // The headline regression. This bean used to be an unconditional @Component, so a fresh
            // production database came up with admin/password holding ROLE_ADMIN — the role that
            // gates credit, debit, transfer, payout and patient deletion.
            runner.run(context -> assertThat(context)
                    .as("no configuration at all must mean no seeded admin")
                    .doesNotHaveBean(DataSeeder.class));
        }

        @Test
        @DisplayName("stays absent when explicitly disabled")
        void absentWhenDisabled() {
            runner.withPropertyValues("auth.seed-default-admin=false")
                    .run(context -> assertThat(context).doesNotHaveBean(DataSeeder.class));
        }

        @Test
        @DisplayName("is present only on an explicit true — so local dev still works")
        void presentWhenExplicitlyEnabled() {
            runner.withPropertyValues("auth.seed-default-admin=true")
                    .run(context -> assertThat(context).hasSingleBean(DataSeeder.class));
        }

        @Test
        @DisplayName("a typo'd or unrecognised value does not enable it (fail-closed)")
        void failsClosedOnGarbage() {
            // @ConditionalOnProperty matches havingValue exactly. This is the property that makes
            // the guard safer than @Profile("!prod"): anything other than a deliberate "true" —
            // including "TRUE ", "yes", "1" or a typo — leaves it off.
            runner.withPropertyValues("auth.seed-default-admin=yes")
                    .run(context -> assertThat(context).doesNotHaveBean(DataSeeder.class));
            runner.withPropertyValues("auth.seed-default-admin=1")
                    .run(context -> assertThat(context).doesNotHaveBean(DataSeeder.class));
        }
    }

    @Nested
    @DisplayName("JWT signing key")
    class SigningKey {

        @Test
        @DisplayName("loads a persistent key from disk, keeping the kid stable across restarts")
        void loadsPersistentKey(@TempDir Path tempDir) throws Exception {
            RSAKey onDisk = new RSAKeyGenerator(2048).keyID("stable-kid").generate();
            Path keyFile = tempDir.resolve("jwt-signing-key.json");
            Files.writeString(keyFile, onDisk.toJSONString());

            new ApplicationContextRunner()
                    .withUserConfiguration(JwtKeysConfig.class)
                    .withPropertyValues("auth.jwt.key-location=file:" + keyFile)
                    .run(context -> {
                        RSAKey loaded = context.getBean(RSAKey.class);
                        // A stable kid is the whole point: resource servers cache JWKS by kid, so a
                        // kid that changed per start-up would invalidate their cache every deploy.
                        assertThat(loaded.getKeyID()).isEqualTo("stable-kid");
                        assertThat(loaded.getModulus()).isEqualTo(onDisk.getModulus());
                        assertThat(loaded.isPrivate())
                                .as("a key with no private half can sign nothing")
                                .isTrue();
                    });
        }

        @Test
        @DisplayName("two separate boots of the same file produce the SAME key — the actual fix")
        void sameKeyAcrossRestarts(@TempDir Path tempDir) throws Exception {
            Path keyFile = tempDir.resolve("jwt-signing-key.json");
            Files.writeString(keyFile, new RSAKeyGenerator(2048).keyID("persisted").generate().toJSONString());

            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withUserConfiguration(JwtKeysConfig.class)
                    .withPropertyValues("auth.jwt.key-location=file:" + keyFile);

            // Modelling the two failure modes at once: a restart (same file, new context) and two
            // replicas (two contexts over one mounted secret). Both must agree on the key, or
            // tokens minted by one are rejected by the other.
            String[] first = new String[1];
            runner.run(context -> first[0] = context.getBean(RSAKey.class).toJSONString());
            runner.run(context -> assertThat(context.getBean(RSAKey.class).toJSONString())
                    .as("a restart or a second replica must sign with the same key")
                    .isEqualTo(first[0]));
        }

        @Test
        @DisplayName("a set-but-missing path FAILS START-UP rather than falling back silently")
        void missingKeyFailsFast() {
            // The important half of the design. Falling back to an ephemeral key here would turn a
            // mistyped mount path into exactly the silent breakage this change removes — the
            // service would start, look healthy, and quietly invalidate tokens on every restart.
            new ApplicationContextRunner()
                    .withUserConfiguration(JwtKeysConfig.class)
                    .withPropertyValues("auth.jwt.key-location=file:/nonexistent/jwt-key.json")
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining("no such resource exists"));
        }

        @Test
        @DisplayName("a public-only JWK is rejected — it cannot sign")
        void publicOnlyKeyIsRejected(@TempDir Path tempDir) throws Exception {
            Path keyFile = tempDir.resolve("public-only.json");
            // The plausible operator mistake: mounting what /oauth2/jwks serves.
            Files.writeString(
                    keyFile, new RSAKeyGenerator(2048).keyID("pub").generate().toPublicJWK().toJSONString());

            new ApplicationContextRunner()
                    .withUserConfiguration(JwtKeysConfig.class)
                    .withPropertyValues("auth.jwt.key-location=file:" + keyFile)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining("no private key"));
        }

        @Test
        @DisplayName("falls back to an ephemeral key only when no location is configured")
        void ephemeralOnlyWhenUnconfigured() {
            new ApplicationContextRunner()
                    .withUserConfiguration(JwtKeysConfig.class)
                    .run(context -> {
                        // Kept deliberately: zero-setup local development. It now warns loudly
                        // instead of being silent, which is what let this reach a readiness review.
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(RSAKey.class).isPrivate()).isTrue();
                    });
        }
    }

    /**
     * The collaborators {@link DataSeeder} needs, so the conditional wiring is what is under test.
     *
     * <p><b>{@code @TestConfiguration}, not {@code @Configuration}.</b> This class lives in the
     * {@code ...authservice.config} package, which {@code @SpringBootTest}'s component scan reaches
     * — so as a plain {@code @Configuration} its {@code passwordEncoder} bean collided with
     * {@code SecurityConfig}'s and broke the start-up of every integration test in the service, not
     * just this one. {@code @TestConfiguration} is excluded from scanning and applies only where it
     * is named explicitly.
     */
    @TestConfiguration
    static class SeederSupport {

        @Bean
        AppUserRepository appUserRepository() {
            return Mockito.mock(AppUserRepository.class);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    /**
     * Imports {@link DataSeeder} so its {@code @ConditionalOnProperty} is evaluated by the runner.
     *
     * <p>It must be passed to {@code withUserConfiguration(...)} as a <b>configuration class</b>,
     * not exposed as a {@code @Bean}. Declaring it as a bean was the first attempt and it silently
     * registered nothing, so {@code presentWhenExplicitlyEnabled} failed while every
     * "bean is absent" assertion passed for the wrong reason — the shape of a test suite that looks
     * green and proves nothing.
     */
    @TestConfiguration
    @org.springframework.context.annotation.Import(DataSeeder.class)
    static class DataSeederRegistration {
    }
}
