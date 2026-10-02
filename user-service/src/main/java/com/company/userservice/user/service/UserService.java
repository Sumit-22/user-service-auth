package com.company.userservice.user.service;

import com.company.userservice.common.exception.ApiException;
import com.company.userservice.user.dto.UserResponse;
import com.company.userservice.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository repo;
    public UserService(UserRepository repo){this.repo=repo;}

    public UserResponse get(UUID id){
        return repo.findSecurityUserById(id).map(UserResponse::from)
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"User not found"));
    }
}
