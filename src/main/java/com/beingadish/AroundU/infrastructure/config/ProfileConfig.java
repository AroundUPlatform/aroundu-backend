package com.beingadish.AroundU.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.beingadish.AroundU.infrastructure.security.CustomAccessDeniedHandler;
import com.beingadish.AroundU.infrastructure.security.CustomAuthenticationEntryPoint;
import com.beingadish.AroundU.infrastructure.security.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
public class ProfileConfig {

    @Configuration
    @Profile("dev")
    @RequiredArgsConstructor
    @Slf4j
    static class DevDataSeeder {

        @Value("${feature.enable-dummy-data:false}")
        private boolean seedDummyData;

        @Bean
        CommandLineRunner seedDevData() {
            return args -> {
                if (!seedDummyData) {
                    log.info("DevDataSeeder skipped (feature flag disabled)");
                    return;
                }
                // Add lightweight, non-sensitive seed hooks here if needed
                log.info("DevDataSeeder running - add sample data seeding as required for local development");
            };
        }
    }

    @Configuration
    @EnableWebSecurity
    @RequiredArgsConstructor
    @Profile("prod")
    static class ProductionSecurityConfig {

        private static final String ADMIN = "ADMIN";
        private static final String CLIENT = "CLIENT";
        private static final String WORKER = "WORKER";

        private static final String[] AUTH_WHITELIST = {
            "/api/v1/auth/**",
            "/api/v1/client/register",
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus",
            "/ws/chat",
            "/ws/chat/**"
        };

        private final JwtAuthenticationFilter jwtAuthenticationFilter;
        private final UserDetailsService userDetailsService;

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
            return config.getAuthenticationManager();
        }

        @Bean
        public DaoAuthenticationProvider authenticationProvider() {
            DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
            authProvider.setUserDetailsService(userDetailsService);
            authProvider.setPasswordEncoder(passwordEncoder());
            return authProvider;
        }

        @Bean
        public SecurityFilterChain productionFilterChain(HttpSecurity http,
                                                         CustomAccessDeniedHandler accessDeniedHandler,
                                                         CustomAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
            http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(exception -> exception
                    .accessDeniedHandler(accessDeniedHandler)
                    .authenticationEntryPoint(authenticationEntryPoint)
                    )
                    .headers(headers -> headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; object-src 'none'; frame-ancestors 'none'; upgrade-insecure-requests"))
                    .httpStrictTransportSecurity(Customizer.withDefaults())
                    .referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER))
                    )
                    .authorizeHttpRequests(auth -> auth
                    .requestMatchers(AUTH_WHITELIST).permitAll()
                    // Actuator endpoints restricted to ADMIN
                    .requestMatchers("/actuator/**").hasRole(ADMIN)
                    .requestMatchers("/api/v1/client/all", "/api/v1/worker/all").hasRole(ADMIN)
                    .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                    .requestMatchers("/api/v1/client/**").hasAnyRole(CLIENT, ADMIN)
                    .requestMatchers("/api/v1/worker/**").hasAnyRole(WORKER, ADMIN)
                    .requestMatchers("/api/v1/jobs/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/bids/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/payments/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/reviews/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/chat/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/users/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/skills/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                    .requestMatchers("/api/v1/public/**").authenticated()
                    .anyRequest().authenticated()
                    )
                    .authenticationProvider(authenticationProvider())
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }

    @Configuration
    @Profile({"dev", "test", "preprod"})
    @Slf4j
    static class SwaggerConfig {

        @Bean
        CommandLineRunner swaggerProfileLogger(@Value("${feature.enable-swagger:true}") boolean swaggerEnabled) {
            return args -> log.info("Swagger availability (non-prod): {}", swaggerEnabled);
        }
    }
}
