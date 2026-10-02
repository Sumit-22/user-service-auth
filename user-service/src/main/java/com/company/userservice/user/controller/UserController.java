package com.company.userservice.user.controller;

import com.company.userservice.user.dto.UserResponse;
import com.company.userservice.user.service.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;
    public UserController(UserService service){this.service=service;}

    @GetMapping("/me")
    public UserResponse me(JwtAuthenticationToken auth){
        return service.get(UUID.fromString(auth.getToken().getSubject()));
    }

    @PreAuthorize("hasAuthority('USER_READ')")
    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id){return service.get(id);}
}
