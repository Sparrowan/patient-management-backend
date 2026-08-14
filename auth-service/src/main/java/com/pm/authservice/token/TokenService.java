package com.pm.authservice.token;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.pm.authservice.dto.TokenResponse;

/**
 * Builds and signs the access token from a successful {@link Authentication}. The claims are the
 * "who + what": {@code sub} (username), {@code roles} (authorities), plus {@code iss}/{@code iat}/
 * {@code exp}. A resource server later reads exactly these to authorize a request.
 */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final Duration ttl;

    public TokenService(
            JwtEncoder jwtEncoder,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.ttl}") Duration ttl) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public TokenResponse issue(Authentication authentication) {
        Instant now = Instant.now();
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" "));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(authentication.getName())
                .claim("roles", roles)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", ttl.toSeconds());
    }
}
