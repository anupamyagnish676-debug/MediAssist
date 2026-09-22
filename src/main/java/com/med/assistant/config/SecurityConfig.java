package com.med.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frame -> frame.disable())) // Allows PDF & TV screen embeds
            .securityContext(context -> context.securityContextRepository(securityContextRepository()))
            .authorizeHttpRequests(auth -> auth
                // Public Authentication Endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()

                // Public WhatsApp & Patient Simulator Endpoints
                .requestMatchers("/api/v1/whatsapp/**").permitAll()
                .requestMatchers("/api/v1/simulator/**").permitAll()
                .requestMatchers("/api/v1/appointments/**").permitAll()
                .requestMatchers("/api/v1/hospitals/**").permitAll()

                // Super Admin Scoped APIs
                .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")

                // Hospital Manager Scoped APIs
                .requestMatchers("/api/v1/hospital-manager/**").hasRole("HOSPITAL_MANAGER")

                // Web Pages & Static Assets
                .requestMatchers("/**", "/*.html", "/css/**", "/js/**", "/h2-console/**").permitAll()

                .anyRequest().authenticated()
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(200))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );

        return http.build();
    }
}
