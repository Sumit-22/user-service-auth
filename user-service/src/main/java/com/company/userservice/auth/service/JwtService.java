package com.company.userservice.auth.service;

import com.company.userservice.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final long accessSeconds;

    public JwtService(JwtEncoder encoder,
                      @Value("${security.jwt.issuer}") String issuer,
                      @Value("${security.jwt.access-token-seconds}") long accessSeconds) {
        this.encoder=encoder; this.issuer=issuer; this.accessSeconds=accessSeconds;
    }

    public String createAccessToken(User user) {
        Instant now=Instant.now();
        Set<String> roles=user.getRoles().stream().map(r->r.getName()).collect(java.util.stream.Collectors.toSet());
        Set<String> permissions=user.getRoles().stream().flatMap(r->r.getPermissions().stream())
            .map(p->p.getName()).collect(java.util.stream.Collectors.toSet());

        JwsHeader header=JwsHeader.with(RS256).keyId("auth-key-1").build();
        JwtClaimsSet claims=JwtClaimsSet.builder()
            .issuer(issuer).subject(user.getId().toString())
            .issuedAt(now).expiresAt(now.plusSeconds(accessSeconds))
            .claim("email",user.getEmail()).claim("roles",roles).claim("permissions",permissions)
            .build();
        return encoder.encode(JwtEncoderParameters.from(header,claims)).getTokenValue();
    }

    public long expiresInSeconds(){return accessSeconds;}
}
