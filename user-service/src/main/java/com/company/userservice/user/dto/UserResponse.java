package com.company.userservice.user.dto;
import com.company.userservice.user.entity.User;
import java.util.*;
import java.util.stream.Collectors;

public record UserResponse(UUID id,String email,String firstName,String lastName,
                           boolean enabled,boolean emailVerified,Set<String> roles,Set<String> permissions) {
    public static UserResponse from(User u) {
        Set<String> roles=u.getRoles().stream().map(r->r.getName()).collect(Collectors.toUnmodifiableSet());
        Set<String> perms=u.getRoles().stream().flatMap(r->r.getPermissions().stream())
            .map(p->p.getName()).collect(Collectors.toUnmodifiableSet());
        return new UserResponse(u.getId(),u.getEmail(),u.getFirstName(),u.getLastName(),
            u.isEnabled(),u.isEmailVerified(),roles,perms);
    }
}
