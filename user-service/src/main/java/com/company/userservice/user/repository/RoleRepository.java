package com.company.userservice.user.repository;

import com.company.userservice.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByName(String name);
}
