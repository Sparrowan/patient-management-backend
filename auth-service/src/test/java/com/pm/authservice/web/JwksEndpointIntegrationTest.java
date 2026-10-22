package com.pm.authservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.pm.authservice.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The JWKS endpoint — the root of the whole system's trust chain.
 *
 * <p>Every resource server (patient, billing, analytics, the gateway) points its
 * {@code jwk-set-uri} here, fetches this document once and caches it, and verifies every token
 * signature against it. Two failure modes matter, and they fail in opposite directions:
 *
 * <ul>
 *   <li><b>Leak the private key</b> and anyone who reads this URL can mint tokens for any user with
 *       any role.</li>
 *   <li><b>Require authentication</b> on it and every resource server fails to start verifying
 *       tokens, because they fetch this before they have a token to fetch it with. It must stay
 *       public, which is safe precisely because it holds only public material.</li>
 * </ul>
 *
 * <p><b>On the private-key assertion, and why it is kept despite being belt-and-braces.</b> It was
 * mutation-tested: removing {@code RSAKey.toPublicJWK()} from the controller — publishing the full
 * keypair — did <em>not</em> fail this test. Probing Nimbus directly explains why.
 * {@code JWKSet.toJSONObject()} omits private material unconditionally, even when passed
 * {@code false} and even for a key whose {@code isPrivate()} is {@code true}:
 *
 * <pre>
 *   full.isPrivate()                 = true
 *   toJSONObject()      contains "d" = false
 *   toJSONObject(true)  contains "d" = false
 *   toJSONObject(false) contains "d" = false
 * </pre>
 *
 * <p>So the leak is prevented by the library, not by our one call, and no realistic edit to this
 * controller can make this assertion fail. That makes it a <b>guard against a dependency or
 * serialization change</b> rather than a regression test for our own code — the distinction is
 * recorded here so nobody mistakes a green tick for proof that {@code toPublicJWK()} is load-bearing.
 * The assertions that genuinely constrain our code are the public-reachability and
 * key-identity ones below, both of which a mutant does break.
 */
@DisplayName("JWKS endpoint (integration)")
class JwksEndpointIntegrationTest extends AbstractIntegrationTest {

    private static final String JWKS = "/oauth2/jwks";

    @Autowired private TestRestTemplate rest;
    @Autowired private RSAKey rsaKey;

    @Test
    @DisplayName("is reachable without a token — resource servers fetch it before they have one")
    void isPubliclyReachable() {
        ResponseEntity<String> response = rest.getForEntity(JWKS, String.class);

        assertThat(response.getStatusCode())
                .as("a 401 here would stop every other service from verifying any token at all")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    @DisplayName("never serves the private key (guards the serializer, not our own call)")
    void doesNotLeakThePrivateKey() throws Exception {
        String body = rest.getForEntity(JWKS, String.class).getBody();

        // The RSA private-key JWK members. Any one of them present means the signing key is public
        // and the entire authentication scheme is void.
        assertThat(body)
                .as("'d' is the RSA private exponent — if it is here, anyone can mint admin tokens")
                .doesNotContain("\"d\"")
                .doesNotContain("\"p\"")
                .doesNotContain("\"q\"")
                .doesNotContain("\"dp\"")
                .doesNotContain("\"dq\"")
                .doesNotContain("\"qi\"");

        // Belt and braces: ask Nimbus itself, rather than trusting string matching on JSON.
        JWKSet served = JWKSet.parse(body);
        assertThat(served.getKeys()).hasSize(1);
        assertThat(served.getKeys().get(0).isPrivate())
                .as("Nimbus agrees this is a public-only key")
                .isFalse();
    }

    @Test
    @DisplayName("publishes the public half of the key that actually signs tokens")
    void publishesTheSigningKeysPublicHalf() throws Exception {
        JWKSet served = JWKSet.parse(rest.getForEntity(JWKS, String.class).getBody());
        RSAKey publishedKey = (RSAKey) served.getKeys().get(0);

        // If the kid or the modulus did not match the signing key, verification would fail for every
        // token while both halves looked individually fine.
        assertThat(publishedKey.getKeyID()).isEqualTo(rsaKey.getKeyID());
        assertThat(publishedKey.getModulus()).isEqualTo(rsaKey.toPublicJWK().getModulus());
        assertThat(publishedKey.getAlgorithm() == null ? "RS256" : publishedKey.getAlgorithm().getName())
                .isEqualTo("RS256");
    }
}
