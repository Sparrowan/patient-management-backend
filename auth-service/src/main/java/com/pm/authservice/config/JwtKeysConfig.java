package com.pm.authservice.config;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StreamUtils;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import lombok.RequiredArgsConstructor;

/**
 * Signing keys. We sign JWTs with an RSA <b>private</b> key; resource servers verify with the
 * matching <b>public</b> key, which they fetch from our JWKS endpoint. (Asymmetric on purpose:
 * only the issuer can mint tokens, but anyone can verify — no shared secret to leak.)
 *
 * <p><b>The key is loaded from {@code auth.jwt.key-location} when set, and only generated in
 * memory when it is not.</b> Generating per start-up was the original behaviour and it breaks
 * production in two ways, neither of them subtle:
 *
 * <ul>
 *   <li><b>Every restart invalidates every live token.</b> New keypair, new signature; every user is
 *       logged out mid-session on each deploy.</li>
 *   <li><b>Auth cannot be scaled past one instance.</b> Two replicas generate two different keys, so
 *       a token minted by replica A fails verification against replica B's JWKS and authentication
 *       becomes a coin flip. Every other service in this system is horizontally scalable; this one
 *       bean was the reason auth was not.</li>
 * </ul>
 *
 * <p>The ephemeral path is kept because it makes local development zero-setup, but it now
 * <b>announces itself at WARN</b> rather than being silent. A silent fallback is how this reached
 * production-readiness review in the first place: the javadoc said "production loads a persistent
 * key" and nothing enforced or even reported it.
 *
 * <p><b>Format:</b> a JWK JSON document containing the private key (what
 * {@code RSAKey.toJSONString()} produces). Mount it as a Kubernetes Secret / Vault file and point
 * {@code auth.jwt.key-location} at it (any Spring resource URI — {@code file:}, {@code classpath:}).
 * The {@code kid} travels inside the document, which is what keeps it stable across restarts so
 * cached JWKS entries at the resource servers stay valid.
 *
 * <p><b>Still out of scope here:</b> rotation. Serving two keys during an overlap window (sign with
 * the new, verify with both) needs a key <em>set</em> on disk rather than one key, and is tracked in
 * ROADMAP. Loading a stable key is the prerequisite for it, not a substitute.
 */
@Configuration
@RequiredArgsConstructor
public class JwtKeysConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeysConfig.class);

    private final ResourceLoader resourceLoader;

    /** Empty means "generate an ephemeral key" — convenient locally, unusable in production. */
    @Value("${auth.jwt.key-location:}")
    private String keyLocation;

    /**
     * The RSA keypair as a Nimbus {@link RSAKey} with a key id (kid) so clients can pick the right
     * key.
     */
    @Bean
    public RSAKey rsaKey() {
        if (keyLocation == null || keyLocation.isBlank()) {
            log.warn("No auth.jwt.key-location set — generating an EPHEMERAL RSA signing key. "
                    + "Every restart invalidates all live tokens, and multiple instances will each "
                    + "sign with a different key, so authentication fails across replicas. "
                    + "Acceptable for local development only.");
            return generateEphemeralKey();
        }
        return loadKey(keyLocation);
    }

    /** The key set the encoder signs from (and, public-half only, what the JWKS endpoint serves). */
    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** Signs JWTs — Spring's standard encoder, backed by the JWK source. */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    private RSAKey loadKey(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            // Fail fast and loudly. Falling back to an ephemeral key here would turn a
            // misconfigured mount into the exact silent failure this change exists to remove.
            throw new IllegalStateException(
                    "auth.jwt.key-location is set to '" + location + "' but no such resource exists. "
                            + "Refusing to fall back to an ephemeral key: that would silently break "
                            + "token validity across restarts and replicas.");
        }
        try (var input = resource.getInputStream()) {
            RSAKey key = RSAKey.parse(StreamUtils.copyToString(input, StandardCharsets.UTF_8));
            if (!key.isPrivate()) {
                throw new IllegalStateException(
                        "The JWK at '" + location + "' has no private key, so nothing can be signed. "
                                + "Provide the full private JWK, not the public half served by /oauth2/jwks.");
            }
            log.info("Loaded RSA signing key from {} (kid={})", location, key.getKeyID());
            return key;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the JWK at '" + location + "'", e);
        }
    }

    private static RSAKey generateEphemeralKey() {
        KeyPair keyPair = generateRsaKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }
}
