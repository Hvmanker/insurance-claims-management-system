package com.sunil.insurance.claims.service;

import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.domain.PortalUserRepository;
import com.sunil.insurance.claims.domain.UserRole;
import com.sunil.insurance.claims.web.RegisterRequest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class PortalUserService {
    private final PortalUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public PortalUserService(PortalUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public PortalUser register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(CONFLICT, "An account already exists for that email");
        }
        return users.save(new PortalUser(
                UUID.randomUUID(),
                email,
                request.fullName().trim(),
                passwordEncoder.encode(request.password()),
                UserRole.INSURED,
                Instant.now()
        ));
    }

    @Transactional(readOnly = true)
    public PortalUser currentUser(Principal principal) {
        if (principal == null) {
            throw new UsernameNotFoundException("Authentication is required");
        }
        return users.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }
}
