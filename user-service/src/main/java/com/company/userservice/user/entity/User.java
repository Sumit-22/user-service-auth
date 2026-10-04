package com.company.userservice.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable=false, unique=true, length=320)
    private String email;

    @Column(nullable=false, length=100)
    private String passwordHash;

    @Column(nullable=false, length=100)
    private String firstName;

    @Column(nullable=false, length=100)
    private String lastName;

    @Column(nullable=false)
    private boolean enabled = true;

    @Column(nullable=false)
    private boolean emailVerified = false;

    @Column(nullable=false)
    private Instant createdAt;

    @Column(nullable=false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
        joinColumns = @JoinColumn(name="user_id"),
        inverseJoinColumns = @JoinColumn(name="role_id"))
    private Set<Role> roles = new HashSet<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId(){ return id; }
    public String getEmail(){ return email; }
    public void setEmail(String v){ email=v; }
    public String getPasswordHash(){ return passwordHash; }
    public void setPasswordHash(String v){ passwordHash=v; }
    public String getFirstName(){ return firstName; }
    public void setFirstName(String v){ firstName=v; }
    public String getLastName(){ return lastName; }
    public void setLastName(String v){ lastName=v; }
    public boolean isEnabled(){ return enabled; }
    public void setEnabled(boolean v){ enabled=v; }
    public boolean isEmailVerified(){ return emailVerified; }
    public void setEmailVerified(boolean v){ emailVerified=v; }
    public Instant getCreatedAt(){ return createdAt; }
    public Instant getUpdatedAt(){ return updatedAt; }
    public Set<Role> getRoles(){ return roles; }
}
