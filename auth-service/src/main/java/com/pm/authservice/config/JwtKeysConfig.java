package com.pm.authservice.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Signing keys. We sign JWTs with an RSA <b>private</b> key; resource servers verify with the
 * matching <b>public</b> key, which they fetch from our JWKS endpoint. (Asymmetric on purpose:
 * only the issuer can mint tokens, but anyone can verify — no shared secret to leak.)
 *
 * <p>The keypair is generated in-memory at startup — fine for dev. Production loads a persistent
 * key from a keystore/Vault, so a restart doesn't invalidate every live token or rotate the JWKS.
 */
@Configuration
public class JwtKeysConfig {

    /** The RSA keypair as a Nimbus {@link RSAKey} with a key id (kid) so clients can pick the right key. */
    @Bean
    public RSAKey rsaKey() {
        KeyPair keyPair = generateRsaKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
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
