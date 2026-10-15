package com.sistemaspalacios.billing.infrastructure.security;

import com.sistemaspalacios.billing.service.access.CurrentAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuthenticationModeSafetyTest {
    @Test void rejectsAuthenticationBypassOutsideExplicitLocalOrTestProfile() {
        assertThrows(IllegalStateException.class, () -> config(false, "postgres").validateAuthenticationMode());
        assertThrows(IllegalStateException.class, () -> config(false, "docker", "local").validateAuthenticationMode());
        assertThrows(IllegalStateException.class, () -> config(false, "production", "test").validateAuthenticationMode());
        assertDoesNotThrow(() -> config(false, "postgres", "local").validateAuthenticationMode());
        assertDoesNotThrow(() -> config(true, "production").validateAuthenticationMode());
    }
    private SecurityConfig config(boolean enabled, String... profiles) {
        var config = new SecurityConfig(mock(RestAuthenticationEntryPoint.class), mock(RestAccessDeniedHandler.class), mock(CurrentAccessService.class));
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        ReflectionTestUtils.setField(config, "environment", environment);
        ReflectionTestUtils.setField(config, "authEnabled", enabled);
        return config;
    }
}
