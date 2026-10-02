package com.company.userservice.auth.dto;
public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, String tokenType) {}
