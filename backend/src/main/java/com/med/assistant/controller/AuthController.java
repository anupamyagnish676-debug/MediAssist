package com.med.assistant.controller;

import com.med.assistant.model.User;
import com.med.assistant.repository.UserRepository;
import com.med.assistant.service.AuditLogService;
import com.med.assistant.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final LoginAttemptService loginAttemptService;
    private final AuditLogService auditLogService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          SecurityContextRepository securityContextRepository,
                          LoginAttemptService loginAttemptService,
                          AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.loginAttemptService = loginAttemptService;
        this.auditLogService = auditLogService;
    }

    public record LoginRequest(String email, String password) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record UserProfileResponse(
            Long id,
            String email,
            String fullName,
            String role,
            Long hospitalId,
            String hospitalName,
            boolean mustChangePassword
    ) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        if (req.email() == null || req.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email and password are required."));
        }

        String username = req.email().trim();
        String clientIp = extractClientIp(request);

        if (loginAttemptService.isLocked(username)) {
            long remaining = loginAttemptService.getLockoutRemainingSeconds(username);
            long minutes = Math.max(1, (remaining + 59) / 60);
            auditLogService.log(AuditLogService.AuditEvent.LOGIN_LOCKED, username,
                    "Account locked out due to multiple failed login attempts. Remaining seconds: " + remaining, clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Account is temporarily locked due to too many failed login attempts. Please try again in " + minutes + " minute(s)."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(username);
        boolean passwordMatches = false;
        if (userOpt.isPresent()) {
            String dbPass = userOpt.get().getPassword();
            if (dbPass != null) {
                if (passwordEncoder.matches(req.password(), dbPass) || passwordEncoder.matches(req.password().trim(), dbPass)) {
                    passwordMatches = true;
                } else if (req.password().trim().equals(dbPass.trim())) {
                    // Plaintext legacy password match: upgrade to BCrypt immediately
                    passwordMatches = true;
                    userOpt.get().setPassword(passwordEncoder.encode(req.password().trim()));
                    userRepository.save(userOpt.get());
                }
            }
        }

        if (userOpt.isEmpty() || !passwordMatches) {
            loginAttemptService.recordFailure(username);
            auditLogService.log(AuditLogService.AuditEvent.LOGIN_FAILED, username, "Invalid email or password", clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid email or password. Note: passwords are case-sensitive."));
        }

        User user = userOpt.get();
        if (!user.isActive()) {
            loginAttemptService.recordFailure(username);
            auditLogService.log(AuditLogService.AuditEvent.LOGIN_FAILED, username, "Account is deactivated", clientIp);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "This account has been deactivated."));
        }

        loginAttemptService.recordSuccess(username);
        auditLogService.log(AuditLogService.AuditEvent.LOGIN_SUCCESS, username, "User login successful for role " + user.getRole().name(), clientIp);

        // Record last login
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // Create authentication
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        Authentication auth = new UsernamePasswordAuthenticationToken(user.getEmail(), null, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        UserProfileResponse profile = new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.getHospital() != null ? user.getHospital().getId() : null,
                user.getHospital() != null ? user.getHospital().getName() : null,
                user.isMustChangePassword()
        );

        return ResponseEntity.ok(profile);
    }

    /**
     * Emergency self-service account unlock & password reset for demo admin accounts.
     */
    @GetMapping("/reset-demo-admin")
    public ResponseEntity<?> resetDemoAdmin() {
        String adminEmail = "admin@mediassist.com";
        loginAttemptService.resetLockout(adminEmail);
        User admin = userRepository.findByEmailIgnoreCase(adminEmail).orElseGet(() -> {
            User u = new User();
            u.setEmail(adminEmail);
            u.setFullName("Platform Administrator");
            u.setRole(User.Role.SUPER_ADMIN);
            return u;
        });
        admin.setPassword(passwordEncoder.encode("Admin@123"));
        admin.setActive(true);
        admin.setRole(User.Role.SUPER_ADMIN);
        userRepository.save(admin);

        // Also reset manager accounts
        String kimsManager = "manager@kims.com";
        loginAttemptService.resetLockout(kimsManager);
        userRepository.findByEmailIgnoreCase(kimsManager).ifPresent(m -> {
            m.setPassword(passwordEncoder.encode("Manager@123"));
            m.setActive(true);
            userRepository.save(m);
        });

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin accounts reset and unlocked successfully!",
                "superAdmin", "admin@mediassist.com / Admin@123",
                "hospitalManager", "manager@kims.com / Manager@123"
        ));
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("authenticated", false));
        }

        String email = auth.getName();
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        User user = userOpt.get();
        UserProfileResponse profile = new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.getHospital() != null ? user.getHospital().getId() : null,
                user.getHospital() != null ? user.getHospital().getName() : null,
                user.isMustChangePassword()
        );

        return ResponseEntity.ok(profile);
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (req.newPassword() == null || req.newPassword().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "New password must be at least 6 characters."));
        }

        String email = auth.getName();
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        User user = userOpt.get();
        if (req.currentPassword() != null && !passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Current password does not match."));
        }

        user.setPassword(passwordEncoder.encode(req.newPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("success", true, "message", "Password changed successfully."));
    }
}
