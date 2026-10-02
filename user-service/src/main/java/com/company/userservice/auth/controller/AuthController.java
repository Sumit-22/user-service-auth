package com.company.userservice.auth.controller;

import com.company.userservice.auth.dto.*;
import com.company.userservice.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}

    @PostMapping("/register")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest r){
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(r));
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest r){return auth.login(r);}

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest r){return auth.refresh(r);}

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest r){
        auth.logout(r); return ResponseEntity.noContent().build();
    }
}
