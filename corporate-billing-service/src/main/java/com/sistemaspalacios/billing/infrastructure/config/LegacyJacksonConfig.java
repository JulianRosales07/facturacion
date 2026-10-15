package com.sistemaspalacios.billing.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serialización de registros de importación; Spring MVC utiliza su propio mapper. */
@Configuration
public class LegacyJacksonConfig {
    @Bean
    public ObjectMapper importObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
