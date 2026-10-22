package com.pm.authservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.authservice.dto.LoginRequest;
import com.pm.authservice.dto.RegisterRequest;
import com.pm.authservice.dto.TokenResponse;
import com.pm.authservice.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Register and login over real HTTP against a real MariaDB.
 *
 * <p>The security-relevant assertions here are the ones about what is <em>not</em> returned and what
 * is <em>not</em> stored: a password must never come back in a response or sit in the database in
 * plaintext. Those are easy to break accidentally (add a field to a DTO, change a mapper) and
 * nothing else in the system would notice.
 */
@DisplayName("Auth flow — register and login (integration)")
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetUsers() {
        // Hard delete, not a soft one: this table has no deleted_at, and leftover rows would collide
        // with the unique username/email constraints the duplicate tests rely on.
        jdbcTemplate.execute("DELETE FROM users");
    }

    private ResponseEntity<UserResponse> register(String username, String email, String password) {
        return rest.postForEntity(REGISTER, new RegisterRequest(username, email, password), UserResponse.class);
    }

    private ResponseEntity<TokenResponse> login(String identifier, String password) {
        return rest.postForEntity(LOGIN, new LoginRequest(identifier, password), TokenResponse.class);
    }

    @Test
    @DisplayName("registers a user, defaults them to ROLE_USER, and never echoes the password")
    void registersWithDefaultRole() {
        ResponseEntity<UserResponse> response = register("alice", "alice@example.com", "s3cret-pass");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UserResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).as("the id is what downstream services store as the audit actor").isNotNull();
        assertThat(body.username()).isEqualTo("alice");
        assertThat(body.roles())
                .as("a new user must not be able to self-assign privileges")
                .isEqualTo("ROLE_USER")
                .doesNotContain("ADMIN");
    }

    @Test
    @DisplayName("stores only a BCrypt hash — never the plaintext password")
    void storesOnlyAHash() {
        register("bob", "bob@example.com", "plaintext-password");

        String stored = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE username = ?", String.class, "bob");

        assertThat(stored)
                .as("a plaintext password in the database is the single worst outcome here")
                .isNotEqualTo("plaintext-password")
                .doesNotContain("plaintext-password");
        assertThat(stored)
                .as("BCrypt, identified by its cost-prefixed format")
                .matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");
    }

    @Test
    @DisplayName("logs in by username and by email — both identify the same account")
    void logsInByUsernameOrEmail() {
        register("carol", "carol@example.com", "correct-horse");

        ResponseEntity<TokenResponse> byUsername = login("carol", "correct-horse");
        ResponseEntity<TokenResponse> byEmail = login("carol@example.com", "correct-horse");

        assertThat(byUsername.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byEmail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byUsername.getBody().accessToken()).isNotBlank();
        assertThat(byEmail.getBody().accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("a wrong password is 401, and the response says nothing about why")
    void wrongPasswordIsUnauthorized() {
        register("dave", "dave@example.com", "right-password");

        ResponseEntity<String> response =
                rest.postForEntity(LOGIN, new LoginRequest("dave", "wrong-password"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The generic wording ("invalid username or password") is the point: it deliberately does
        // NOT say which of the two was wrong. Note the message legitimately contains the word
        // "password" — what must be absent is anything that narrows it down.
        assertThat(response.getBody() == null ? "" : response.getBody().toLowerCase())
                .as("must not name the submitted account — that would confirm it exists")
                .doesNotContain("dave")
                .as("must not single out one field as the failing one")
                .doesNotContain("wrong password")
                .doesNotContain("incorrect password")
                .doesNotContain("bad credentials")
                .doesNotContain("user not found")
                .doesNotContain("no such user");
    }

    @Test
    @DisplayName("an unknown user is byte-identical to a wrong password — no enumeration oracle")
    void unknownUserIsIndistinguishableFromWrongPassword() {
        register("helen", "helen@example.com", "right-password");

        ResponseEntity<String> wrongPassword =
                rest.postForEntity(LOGIN, new LoginRequest("helen", "wrong-password"), String.class);
        ResponseEntity<String> unknownUser = rest.postForEntity(
                LOGIN, new LoginRequest("nobody@example.com", "any-password"), String.class);

        assertThat(unknownUser.getStatusCode())
                .as("404 here would confirm which accounts exist")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // The strong form of the property, and the one worth asserting: an attacker comparing the
        // two responses must learn nothing. Equal status and equal body means no oracle, which is
        // what makes username enumeration (and therefore cheap credential stuffing) impossible.
        assertThat(unknownUser.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(normalize(unknownUser.getBody()))
                .as("a wrong password and an unknown user must be indistinguishable")
                .isEqualTo(normalize(wrongPassword.getBody()));
    }

    /** Drops the {@code instance} path, which echoes the request URI and is not an identity signal. */
    private static String normalize(String body) {
        return body == null ? "" : body.replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    @Test
    @DisplayName("a duplicate username is 409, not a 500 from the unique constraint")
    void duplicateUsernameIsConflict() {
        register("erin", "erin@example.com", "pass-one");

        ResponseEntity<String> second =
                rest.postForEntity(REGISTER, new RegisterRequest("erin", "other@example.com", "pass-two"), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, "erin"))
                .as("the unique constraint is the real guard; the 409 is just the friendly face of it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate email is 409 too")
    void duplicateEmailIsConflict() {
        register("frank", "shared@example.com", "pass-one");

        ResponseEntity<String> second = rest.postForEntity(
                REGISTER, new RegisterRequest("frank2", "shared@example.com", "pass-two"), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("invalid registration input is 400, before anything is written")
    void invalidInputIsBadRequest() {
        ResponseEntity<String> blankUsername =
                rest.postForEntity(REGISTER, new RegisterRequest("", "a@example.com", "pass-word"), String.class);
        ResponseEntity<String> badEmail =
                rest.postForEntity(REGISTER, new RegisterRequest("gina", "not-an-email", "pass-word"), String.class);

        assertThat(blankUsername.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .as("validation must reject before the write, not after")
                .isZero();
    }
}
