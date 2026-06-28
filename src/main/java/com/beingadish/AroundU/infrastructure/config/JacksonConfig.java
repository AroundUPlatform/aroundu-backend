package com.beingadish.AroundU.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Centralises customisation of Spring Boot's auto-configured HTTP {@code ObjectMapper}.
 * <p>
 * Using a {@link Jackson2ObjectMapperBuilderCustomizer} (rather than defining a new
 * {@code ObjectMapper} bean) keeps Spring Boot's defaults intact — notably the
 * auto-registered {@code JavaTimeModule} — while layering in our rules:
 * <ul>
 *   <li>{@code NON_NULL} inclusion — omit null fields from every response to trim payloads.</li>
 *   <li>ISO-8601 dates (no epoch timestamps) — made explicit even though it is the Boot default.</li>
 *   <li>Tolerant deserialization — ignore unknown JSON properties instead of failing.</li>
 * </ul>
 * This affects only the MVC/HTTP mapper. The Redis cache mapper in
 * {@link RedisConfig} is deliberately separate because it requires default typing,
 * which must never be enabled on the HTTP mapper.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
                );
    }
}
