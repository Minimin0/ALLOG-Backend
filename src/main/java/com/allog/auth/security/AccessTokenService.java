package com.allog.auth.security;

import com.allog.auth.config.AuthTokenProperties;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;

import static com.allog.auth.config.LocalAuthConfiguration.ISSUER;

@Service
public class AccessTokenService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthTokenProperties properties;

    public AccessTokenService(JwtEncoder encoder, JwtDecoder decoder, AuthTokenProperties properties) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
    }

    public IssuedToken issue(Long userId) {
        return issueAt(userId, Instant.now());
    }

    IssuedToken issueAt(Long userId, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, properties.ttl().toSeconds());
    }

    public AllogPrincipal verify(String token) {
        try {
            long userId = Long.parseLong(decoder.decode(token).getSubject());
            if (userId <= 0) throw new NumberFormatException("non-positive subject");
            return new AllogPrincipal(userId);
        } catch (JwtException | NumberFormatException exception) {
            throw new BadCredentialsException("Invalid bearer token", exception);
        }
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
