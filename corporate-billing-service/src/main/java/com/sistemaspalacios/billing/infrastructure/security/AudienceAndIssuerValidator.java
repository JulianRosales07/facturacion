package com.sistemaspalacios.billing.infrastructure.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Validates the 'aud' (audience) and 'iss' (issuer) claims of a JWT.
 */
public class AudienceAndIssuerValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;
    private final String expectedIssuer;

    public AudienceAndIssuerValidator(String expectedAudience, String expectedIssuer) {
        this.expectedAudience = expectedAudience;
        this.expectedIssuer = expectedIssuer;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // Validate audience
        if (expectedAudience != null && !expectedAudience.isBlank()) {
            List<String> audience = jwt.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                OAuth2Error error = new OAuth2Error("invalid_audience", "Token audience does not match required service audience", null);
                return OAuth2TokenValidatorResult.failure(error);
            }
        }

        // Validate issuer
        if (expectedIssuer != null && !expectedIssuer.isBlank()) {
            String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
            if (issuer == null || !expectedIssuer.equalsIgnoreCase(issuer)) {
                OAuth2Error error = new OAuth2Error("invalid_issuer", "Token issuer does not match configured issuer", null);
                return OAuth2TokenValidatorResult.failure(error);
            }
        }

        return OAuth2TokenValidatorResult.success();
    }
}
