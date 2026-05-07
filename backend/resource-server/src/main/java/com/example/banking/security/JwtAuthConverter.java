package com.example.banking.security;

import com.example.banking.model.BankUserEntity;
import com.example.banking.model.UserRole;
import com.example.banking.repository.BankUserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts a validated JWT into a Spring Security authentication.
 *
 * On first login, creates a BANK_USERS row from the JWT's sub claim.
 * On subsequent logins, reuses the existing row.
 *
 * The role is read from a custom "role" claim in the JWT (set by the
 * mock auth server's token customizer). If absent (e.g., Google tokens),
 * defaults to CUSTOMER.
 *
 * authentication.getName() returns the local userId (not the JWT sub).
 * This is the same value that all service methods use for ownership checks.
 */
@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final BankUserRepository users;

    public JwtAuthConverter(BankUserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        /*
         * TODO (Day 2 — Step 3): Implement JWT → Spring Security principal conversion.
         *
         * 1. Extract the JWT subject: jwt.getSubject()
         *
         * 2. Extract email from the "email" claim (jwt.getClaimAsString("email")).
         *    If absent, synthesise one: subject + "@mock.local"
         *
         * 3. Extract display name from the "name" claim.
         *    If absent, fall back to subject.
         *
         * 4. Determine the role from the "role" claim:
         *      - If the claim equals "ADMIN" (case-insensitive) → UserRole.ADMIN
         *      - Otherwise → UserRole.CUSTOMER
         *
         * 5. Upsert the local BANK_USERS row:
         *      users.findBySubject(subject)
         *        .orElseGet(() -> users.save(BankUserEntity.newUser(subject, email, name, role)))
         *    This creates the row on first login and reuses it on every subsequent login.
         *
         * 6. Build the authorities list:
         *      List.of(new SimpleGrantedAuthority("ROLE_" + localUser.getRole().name()))
         *
         * 7. Return a JwtAuthenticationToken with:
         *      - the validated jwt
         *      - the authorities list
         *      - principal name = localUser.getUserId()   <—— NOT the JWT subject!
         *    This means Authentication.getName() returns the local userId everywhere.
         *
         * Security note: DO NOT use the JWT subject as the principal name directly.
         * The local userId is the primary key used for ALL ownership checks.
         */
        String subject = jwt.getSubject();
        String rawEmail = jwt.getClaimAsString("email");
        String email = rawEmail != null ? rawEmail : subject + "@mock.local";
        String rawName = jwt.getClaimAsString("name");
        String name = rawName != null ? rawName : subject;

        // Determine role from JWT claim; default to CUSTOMER if not present
        String roleClaim = jwt.getClaimAsString("role");
        UserRole role = "ADMIN".equalsIgnoreCase(roleClaim) ? UserRole.ADMIN : UserRole.CUSTOMER;

        // Upsert: create on first login, find on subsequent logins.
        // Guard against a race condition where two concurrent requests both see
        // Optional.empty() and both attempt the INSERT — the second one hits the
        // UQ_BANK_USERS_SUBJECT constraint.  We catch that and fall back to a
        // plain lookup so the request succeeds instead of returning 500.
        BankUserEntity localUser;
        try {
            localUser = users.findBySubject(subject)
                    .orElseGet(() -> users.save(
                            BankUserEntity.newUser(subject, email, name, role)));
        } catch (DataIntegrityViolationException ex) {
            // Lost the insert race — the row was created by a concurrent request.
            localUser = users.findBySubject(subject)
                    .orElseThrow(() -> ex);
        }

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + localUser.getRole().name()));

        // Set the principal name to the local userId
        return new JwtAuthenticationToken(jwt, authorities, localUser.getUserId());
    }
}
