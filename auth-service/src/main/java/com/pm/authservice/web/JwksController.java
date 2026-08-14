package com.pm.authservice.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import lombok.RequiredArgsConstructor;

/**
 * Publishes the <b>public</b> half of our signing key as a JSON Web Key Set. Resource servers point
 * their {@code jwk-set-uri} here and fetch it (once, then cached) to verify token signatures —
 * which is why they never need a shared secret and never call us per request.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RSAKey rsaKey;

    @GetMapping("/oauth2/jwks")
    public Map<String, Object> jwks() {
        // toPublicJWK() strips the private key — only the public key is ever served.
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
