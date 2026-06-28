package com.beingadish.AroundU.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.beingadish.AroundU.infrastructure.security.CustomAccessDeniedHandler;
import com.beingadish.AroundU.infrastructure.security.CustomAuthenticationEntryPoint;
import com.beingadish.AroundU.infrastructure.security.JwtAuthenticationFilter;
import com.beingadish.AroundU.infrastructure.security.PayloadEncryptionFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String CLIENT = "CLIENT";
    private static final String WORKER = "WORKER";

    private static final String[] AUTH_WHITELIST = {
        "/",
        "/index.html",
        "/frontend/**",
        "/favicon.ico",
        "/css/**",
        "/js/**",
        "/images/**",
        "/webjars/**",
        "/api/v1/auth/**",
        "/api/v1/client/register",
        "/api/v1/worker/register",
        "/v3/api-docs/**",
        "/api-docs/**",
        "/docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/prometheus",
        "/ws/chat",
        "/ws/chat/**",
        "/api/v1/fx/rate"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PayloadEncryptionFilter payloadEncryptionFilter;
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
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           CustomAccessDeniedHandler accessDeniedHandler,
                                           CustomAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .exceptionHandling(exception -> exception
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint(authenticationEntryPoint)
                )
                .authorizeHttpRequests(auth -> auth
                .requestMatchers(AUTH_WHITELIST).permitAll()
                // Actuator endpoints restricted to ADMIN
                .requestMatchers("/actuator/**").hasRole(ADMIN)
                // Admin-only endpoints first
                .requestMatchers("/api/v1/client/all", "/api/v1/worker/all").hasRole(ADMIN)
                .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                // Allow both admin and client on client endpoints
                .requestMatchers("/api/v1/client/**").hasAnyRole(CLIENT, ADMIN)
                // Allow both admin and worker on worker endpoints
                .requestMatchers("/api/v1/worker/**").hasAnyRole(WORKER, ADMIN)
                // Public profile endpoints — any authenticated user
                .requestMatchers("/api/v1/public/**").authenticated()
                // Shared resources: allow admin or relevant role
                .requestMatchers("/api/v1/jobs/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .requestMatchers("/api/v1/bids/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .requestMatchers("/api/v1/bid/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .requestMatchers("/api/v1/payments/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                // Reviews & Chat — any authenticated user
                .requestMatchers("/api/v1/reviews/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .requestMatchers("/api/v1/chat/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .requestMatchers("/api/v1/users/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .requestMatchers("/api/v1/skills/**").hasAnyRole(ADMIN, CLIENT, WORKER)
                .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(payloadEncryptionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
