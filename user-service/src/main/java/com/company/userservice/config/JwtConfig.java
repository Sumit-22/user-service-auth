package com.company.userservice.config;

import com.company.userservice.auth.service.JwtService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.Base64;

@Configuration
public class JwtConfig {
    @Bean
    RSAPrivateKey privateKey(@Value("${security.jwt.private-key}") String value) throws Exception {
        return (RSAPrivateKey) readKey(value, true);
    }

    @Bean
    RSAPublicKey publicKey(@Value("${security.jwt.public-key}") String value) throws Exception {
        return (RSAPublicKey) readKey(value, false);
    }

    @Bean
    JwtEncoder jwtEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        com.nimbusds.jose.jwk.RSAKey jwk = new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey)
            .privateKey(privateKey).keyID("auth-key-1").build();
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
            new com.nimbusds.jose.jwk.JWKSet(jwk)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAPublicKey publicKey, @Value("${security.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private Key readKey(String raw, boolean privateKey) throws Exception {
        String pem = raw;
        if (Files.exists(Path.of(raw))) pem = Files.readString(Path.of(raw));
        pem = pem.replaceAll("-----BEGIN (RSA )?(PRIVATE|PUBLIC) KEY-----","")
                 .replaceAll("-----END (RSA )?(PRIVATE|PUBLIC) KEY-----","")
                 .replaceAll("\\s","");
        byte[] bytes=Base64.getDecoder().decode(pem);
        KeyFactory kf=KeyFactory.getInstance("RSA");
        return privateKey
            ? kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(bytes))
            : kf.generatePublic(new java.security.spec.X509EncodedKeySpec(bytes));
    }
}
