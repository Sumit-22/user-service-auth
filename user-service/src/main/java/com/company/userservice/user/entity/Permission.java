package com.company.userservice.user.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="permissions")
public class Permission {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    private UUID id;

    @Column(nullable=false, unique=true, length=100)
    private String name;

    public UUID getId(){ return id; }
    public String getName(){ return name; }
    public void setName(String v){ name=v; }
}
