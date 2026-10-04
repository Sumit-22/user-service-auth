package com.company.userservice.auth.controller;

import com.company.userservice.auth.dto.*;
import com.company.userservice.auth.service.AuthService;
import com.company.userservice.ratelimit.RateLimit;
import com.company.userservice.ratelimit.RateLimitPolicies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}

    @RateLimit(policy=RateLimitPolicies.REGISTER_IP)
    @PostMapping("/register")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest r){
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(r));
    }

    @RateLimit(policy=RateLimitPolicies.LOGIN_IP)
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest request){
        return auth.login(r, request.getRemoteAddr());
    }

    @RateLimit(policy=RateLimitPolicies.TOKEN_IP)
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest r){return auth.refresh(r);}

    @RateLimit(policy=RateLimitPolicies.TOKEN_IP)
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest r){
        auth.logout(r); return ResponseEntity.noContent().build();
    }
}
