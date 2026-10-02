package com.company.userservice.auth.dto;
import jakarta.validation.constraints.*;

public record RegisterRequest(
    @NotBlank @Email @Size(max=320) String email,
    @NotBlank @Size(min=12,max=100) String password,
    @NotBlank @Size(max=100) String firstName,
    @NotBlank @Size(max=100) String lastName
) {}
