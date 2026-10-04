package com.company.userservice.common.exception;

import com.company.userservice.ratelimit.RateLimitDecision;
import com.company.userservice.ratelimit.RateLimitExceededException;
import com.company.userservice.ratelimit.RateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    @Test
    void rateLimitHeadersReplaceThoseAlreadyWrittenByTheInterceptor() {
        var req=new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var res=new MockHttpServletResponse();
        // The interceptor already passed the looser login-ip policy and wrote its headers.
        res.setHeader(RateLimitInterceptor.LIMIT_HEADER, "20");
        res.setHeader(RateLimitInterceptor.REMAINING_HEADER, "14");

        var entity=new GlobalExceptionHandler().rateLimited(
            new RateLimitExceededException(new RateLimitDecision(false, 5, 0, 179_500)), req, res);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, entity.getStatusCode());
        assertEquals(List.of("5"), res.getHeaders(RateLimitInterceptor.LIMIT_HEADER));
        assertEquals(List.of("0"), res.getHeaders(RateLimitInterceptor.REMAINING_HEADER));
        assertEquals(List.of("180"), res.getHeaders(HttpHeaders.RETRY_AFTER));
        assertTrue(entity.getHeaders().isEmpty(), "headers must not be duplicated via the ResponseEntity");
    }
}
