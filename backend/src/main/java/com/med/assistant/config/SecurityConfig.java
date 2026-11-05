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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

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

    /**
     * CORS: restrict origins to the live Render domain + localhost for dev.
     * Previously was open wildcard (*) — now locked to trusted first-party origins.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "https://mediassist-1hdl.onrender.com",
                "http://localhost:8085",
                "http://localhost:3000"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Hub-Signature-256", "X-Patient-Session-Token"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Security Headers
            .headers(headers -> headers
                // Clickjacking protection — allow same-origin embeds for queue monitor
                .frameOptions(frame -> frame.sameOrigin())
                // HSTS: force HTTPS for 1 year, include subdomains
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                    .preload(true)
                )
                // Content Security Policy: block inline scripts from unknown origins
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                        "default-src 'self'; " +
                        "script-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com; " +
                        "font-src 'self' https://fonts.gstatic.com; " +
                        "img-src 'self' data: blob:; " +
                        "connect-src 'self'; " +
                        "frame-ancestors 'self';"
                    )
                )
                // Referrer Policy: don't leak URL in cross-origin requests
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
                // X-Content-Type-Options: prevent MIME sniffing
                .contentTypeOptions(contentType -> {})
                // X-XSS-Protection
                .xssProtection(xss -> {})
            )
            .securityContext(context -> context.securityContextRepository(securityContextRepository()))
            .authorizeHttpRequests(auth -> auth
                // Public Authentication Endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/apply/**").permitAll()
                .requestMatchers("/api/v1/patient/otp/**").permitAll()
                .requestMatchers("/api/v1/patient/profile/**").permitAll()

                // Public WhatsApp & Patient Simulator Endpoints
                .requestMatchers("/api/v1/whatsapp/**").permitAll()
                .requestMatchers("/api/v1/simulator/**").permitAll()
                .requestMatchers("/api/v1/appointments/**").permitAll()
                .requestMatchers("/api/v1/hospitals/**").permitAll()
                .requestMatchers("/api/v1/reminders/**").permitAll()

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
