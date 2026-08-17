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
import com.pm.authservice.security.AppUserDetails;

/**
 * Builds and signs the access token from a successful {@link Authentication}. The claims are the
 * "who + what": {@code sub} (the <b>stable user id</b> — immutable/never-reassigned, so it's the
 * identifier downstream services persist as the audit "who"), {@code preferred_username} (the
 * human-readable display name — a convenience snapshot, never used for identity), {@code roles}
 * (authorities), plus {@code iss}/{@code iat}/{@code exp}. A resource server reads exactly these.
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
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" "));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(principal.getId().toString())
                .claim("preferred_username", principal.getUsername())
                .claim("roles", roles)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", ttl.toSeconds());
    }
}
