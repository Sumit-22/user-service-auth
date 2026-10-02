package com.company.userservice.user.repository;

import com.company.userservice.user.entity.User;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    @Query("""
        select distinct u from User u
        left join fetch u.roles r
        left join fetch r.permissions
        where lower(u.email)=lower(:email)
    """)
    Optional<User> findSecurityUser(@Param("email") String email);

    @Query("""
        select distinct u from User u
        left join fetch u.roles r
        left join fetch r.permissions
        where u.id=:id
    """)
    Optional<User> findSecurityUserById(@Param("id") UUID id);
}
