package com.company.userservice.auth.entity;

import com.company.userservice.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="refresh_tokens",
       indexes={
           @Index(name="idx_refresh_hash", columnList="token_hash", unique=true),
           @Index(name="idx_refresh_user", columnList="user_id")
       })
public class RefreshToken {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="user_id", nullable=false)
    private User user;

    @Column(name="token_hash", nullable=false, unique=true, length=128)
    private String tokenHash;

    @Column(nullable=false)
    private Instant expiresAt;

    @Column(nullable=false)
    private boolean revoked=false;

    @Column(length=128)
    private String replacedByHash;

    @Column(nullable=false)
    private Instant createdAt;

    public UUID getId(){return id;}
    public User getUser(){return user;}
    public void setUser(User v){user=v;}
    public String getTokenHash(){return tokenHash;}
    public void setTokenHash(String v){tokenHash=v;}
    public Instant getExpiresAt(){return expiresAt;}
    public void setExpiresAt(Instant v){expiresAt=v;}
    public boolean isRevoked(){return revoked;}
    public void setRevoked(boolean v){revoked=v;}
    public String getReplacedByHash(){return replacedByHash;}
    public void setReplacedByHash(String v){replacedByHash=v;}
    public Instant getCreatedAt(){return createdAt;}
    public void setCreatedAt(Instant v){createdAt=v;}
}
