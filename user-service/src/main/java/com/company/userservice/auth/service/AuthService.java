package com.company.userservice.auth.service;

import com.company.userservice.auth.dto.*;
import com.company.userservice.common.exception.ApiException;
import com.company.userservice.user.entity.*;
import com.company.userservice.user.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthenticationManager authenticationManager;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
                       JwtService jwt, RefreshTokenService refresh,
                       AuthenticationManager authenticationManager) {
        this.users=users; this.roles=roles; this.encoder=encoder; this.jwt=jwt;
        this.refresh=refresh; this.authenticationManager=authenticationManager;
    }

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        if(users.existsByEmailIgnoreCase(req.email()))
            throw new ApiException(HttpStatus.CONFLICT,"Email already registered");

        Role role=roles.findByName("USER").orElseThrow();
        User u=new User();
        u.setEmail(req.email().trim().toLowerCase());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setFirstName(req.firstName().trim());
        u.setLastName(req.lastName().trim());
        u.getRoles().add(role);
        users.save(u);
        return tokens(u);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.email().trim().toLowerCase(),req.password()));
        } catch (AuthenticationException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Invalid email or password");
        }
        User u=users.findSecurityUser(req.email()).orElseThrow();
        return tokens(u);
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest req) {
        RefreshTokenService.Rotation rotation=refresh.rotate(req.refreshToken());
        return new TokenResponse(jwt.createAccessToken(rotation.user()),rotation.refreshToken(),
            jwt.expiresInSeconds(),"Bearer");
    }

    @Transactional
    public void logout(RefreshRequest req){ refresh.revoke(req.refreshToken()); }

    private TokenResponse tokens(User u) {
        return new TokenResponse(jwt.createAccessToken(u),refresh.create(u),
            jwt.expiresInSeconds(),"Bearer");
    }
}
