package com.company.userservice.auth.service;

import com.company.userservice.user.repository.UserRepository;
import org.springframework.security.core.authority.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class SecurityUserDetailsService implements UserDetailsService {
    private final UserRepository repo;
    public SecurityUserDetailsService(UserRepository repo){this.repo=repo;}

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var u=repo.findSecurityUser(email).orElseThrow(()->new UsernameNotFoundException("Invalid credentials"));
        var authorities=u.getRoles().stream().flatMap(r ->
            java.util.stream.Stream.concat(
                java.util.stream.Stream.of(new SimpleGrantedAuthority("ROLE_"+r.getName())),
                r.getPermissions().stream().map(p->new SimpleGrantedAuthority(p.getName()))
            )).toList();
        return org.springframework.security.core.userdetails.User.withUsername(u.getEmail())
            .password(u.getPasswordHash()).authorities(authorities)
            .disabled(!u.isEnabled()).build();
    }
}
