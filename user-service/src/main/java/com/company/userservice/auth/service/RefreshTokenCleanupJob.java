package com.company.userservice.auth.service;

import com.company.userservice.auth.repository.RefreshTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Component
public class RefreshTokenCleanupJob {
    private final RefreshTokenRepository repo;
    public RefreshTokenCleanupJob(RefreshTokenRepository repo){this.repo=repo;}

    @Scheduled(cron="0 0 * * * *")
    @Transactional
    public void cleanup(){ repo.deleteExpiredOrRevoked(Instant.now()); }
}
