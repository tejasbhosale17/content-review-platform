package com.tejas.contentreview.caseservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableConfigurationProperties(DemoUsersProperties.class)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(DemoUsersProperties props, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(props.users().stream()
                .map(u -> User.withUsername(u.username()).password(encoder.encode(u.password()))
                        .roles(u.roles().toArray(String[]::new)).build())
                .toList());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, e) -> write(mapper, res, HttpStatus.UNAUTHORIZED, "Authentication required");
        AccessDeniedHandler forbidden = (req, res, e) -> write(mapper, res, HttpStatus.FORBIDDEN, "Access denied");
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.authenticationEntryPoint(unauthorized))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**", "/internal/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/cases").hasRole("REPORTER")
                        .requestMatchers(HttpMethod.POST, "/api/cases/*/appeals").hasRole("CREATOR")
                        .anyRequest().authenticated())
                .build();
    }

    private static void write(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse res, HttpStatus status,
                              String message) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), Map.of("timestamp", Instant.now().toString(), "status", status.value(),
                "error", status.getReasonPhrase(), "message", message, "details", List.of()));
    }
}
