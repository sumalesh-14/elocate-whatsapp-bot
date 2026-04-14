package com.elocate.whatsapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Meta webhook calls must be public
                .requestMatchers("/api/whatsapp/webhook").permitAll()
                // Internal calls from elocate-server (protected by shared secret header)
                .requestMatchers("/internal/**").permitAll()
                .anyRequest().denyAll()
            );
        return http.build();
    }

    /** Suppress the auto-generated password warning — no user login needed for this service */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(); // empty — no users needed
    }
}
