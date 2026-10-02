package com.company.userservice.user.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name="roles")
public class Role {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    private UUID id;

    @Column(nullable=false, unique=true, length=50)
    private String name;

    @ManyToMany(fetch=FetchType.EAGER)
    @JoinTable(name="role_permissions",
        joinColumns=@JoinColumn(name="role_id"),
        inverseJoinColumns=@JoinColumn(name="permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public UUID getId(){ return id; }
    public String getName(){ return name; }
    public void setName(String v){ name=v; }
    public Set<Permission> getPermissions(){ return permissions; }
}
