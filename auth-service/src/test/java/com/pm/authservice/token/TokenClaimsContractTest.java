package com.pm.authservice.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.pm.authservice.AbstractIntegrationTest;
import com.pm.authservice.dto.TokenResponse;
import com.pm.authservice.security.AppUserDetails;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * The access token's claim set, treated as a <b>cross-service contract</b> rather than an
 * implementation detail.
 *
 * <p><b>Why this is the first test auth-service gets.</b> These claims are load-bearing for every
 * other service in the system, and each one is read by code that lives in a different repository
 * directory and is deployed separately:
 *
 * <ul>
 *   <li>{@code sub} — patient-service and billing-service persist it as {@code created_by} /
 *       {@code updated_by}. It is the <b>permanent audit record of who moved money</b>. It must be
 *       the stable user <b>id</b>, never the username: a username can be changed, and an audit
 *       trail pointing at a reassignable string rots silently.</li>
 *   <li>{@code roles} — space-separated and <b>already {@code ROLE_}-prefixed</b>, because
 *       patient-service's {@code JwtAuthenticationConverter} is configured with an empty authority
 *       prefix. Emit {@code "ADMIN"} instead of {@code "ROLE_ADMIN"} and
 *       {@code @PreAuthorize("hasRole('ADMIN')")} silently fails closed: admins start getting 403s
 *       on every money movement.</li>
 *   <li>{@code preferred_username} — the display name, a convenience snapshot. Never identity.</li>
 *   <li>{@code iss} / {@code exp} — the resource servers validate both.</li>
 * </ul>
 *
 * <p>Nothing in auth-service's own behaviour breaks if a claim name or format changes here; the
 * damage lands in four other services at runtime. That asymmetry is exactly what a contract test is
 * for, and it is the reason these assertions look like they are stating the obvious.
 */
@DisplayName("Access token claims (cross-service contract)")
class TokenClaimsContractTest extends AbstractIntegrationTest {

    @Autowired private TokenService tokenService;
    @Autowired private RSAKey rsaKey;

    private static Authentication authenticated(UUID userId, String username, String... authorities) {
        AppUserDetails principal = new AppUserDetails(
                userId, username, "irrelevant-hash", true, AuthorityUtils.createAuthorityList(authorities));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private SignedJWT decode(TokenResponse response) throws Exception {
        return SignedJWT.parse(response.accessToken());
    }

    @Test
    @DisplayName("sub is the stable user id, not the username — the audit trail must not rot on a rename")
    void subjectIsTheStableUserId() throws Exception {
        UUID userId = UUID.randomUUID();

        SignedJWT jwt = decode(tokenService.issue(authenticated(userId, "alice", "ROLE_USER")));

        assertThat(jwt.getJWTClaimsSet().getSubject())
                .as("downstream services persist sub as created_by/updated_by; a username there "
                        + "would break the moment someone is renamed")
                .isEqualTo(userId.toString())
                .isNotEqualTo("alice");
    }

    @Test
    @DisplayName("roles arrive space-separated and already ROLE_-prefixed")
    void rolesAreSpaceSeparatedAndPrefixed() throws Exception {
        SignedJWT jwt = decode(
                tokenService.issue(authenticated(UUID.randomUUID(), "root", "ROLE_ADMIN", "ROLE_USER")));

        String roles = jwt.getJWTClaimsSet().getStringClaim("roles");

        assertThat(roles)
                .as("patient-service maps this claim with an EMPTY authority prefix, so the prefix "
                        + "must already be here or hasRole('ADMIN') fails closed")
                .isEqualTo("ROLE_ADMIN ROLE_USER");
        assertThat(roles.split(" "))
                .as("space-separated, not a JSON array — that is the agreed wire format")
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    @DisplayName("preferred_username carries the display name, separate from identity")
    void preferredUsernameIsTheDisplayName() throws Exception {
        UUID userId = UUID.randomUUID();

        SignedJWT jwt = decode(tokenService.issue(authenticated(userId, "alice", "ROLE_USER")));

        assertThat(jwt.getJWTClaimsSet().getStringClaim("preferred_username")).isEqualTo("alice");
        assertThat(jwt.getJWTClaimsSet().getSubject())
                .as("the two must stay distinct: one is a renameable label, the other is identity")
                .isNotEqualTo("alice");
    }

    @Test
    @DisplayName("issuer and expiry are present and in the future — resource servers validate both")
    void issuerAndExpiryAreSet() throws Exception {
        Instant before = Instant.now();

        TokenResponse response = tokenService.issue(authenticated(UUID.randomUUID(), "bob", "ROLE_USER"));
        SignedJWT jwt = decode(response);

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("auth-service");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .as("an already-expired token would be rejected everywhere")
                .isAfter(before);
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isNotNull();
        // The response's advertised lifetime must match the token's, or a client refreshes at the
        // wrong moment and sees spurious 401s.
        assertThat(response.expiresInSeconds())
                .as("expiresIn must describe the token actually issued")
                .isEqualTo(jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond()
                        - jwt.getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond());
    }

    @Test
    @DisplayName("signed RS256 with a kid that matches the JWKS key — asymmetric, so only we can mint")
    void signedWithTheAdvertisedKey() throws Exception {
        SignedJWT jwt = decode(tokenService.issue(authenticated(UUID.randomUUID(), "carol", "ROLE_USER")));

        assertThat(jwt.getHeader().getAlgorithm().getName())
                .as("RS256, not HS256: resource servers verify with the public key and no shared "
                        + "secret ever leaves this service")
                .isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID())
                .as("the kid must identify the key the JWKS endpoint publishes, or verification "
                        + "fails once more than one key is in play")
                .isEqualTo(rsaKey.getKeyID());
    }

    @Test
    @DisplayName("the token type is Bearer — clients build the Authorization header from it")
    void tokenTypeIsBearer() {
        TokenResponse response = tokenService.issue(authenticated(UUID.randomUUID(), "dave", "ROLE_USER"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
    }
}
