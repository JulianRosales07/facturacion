package com.sistemaspalacios.billing.service.security;

import java.util.Optional;

/**
 * Interface to extract the currently authenticated user identifier
 * without coupling the service or domain layer directly to Spring Security.
 */
public interface CurrentUserProvider {

    /**
     * Gets the username / identifier of the currently authenticated principal,
     * or a default "SISTEMA" identifier if running in a system/internal task.
     */
    String getCurrentUsername();

    /**
     * Returns the 'sub' (subject) or principal name if present.
     */
    Optional<String> getCurrentUserSubject();
}
