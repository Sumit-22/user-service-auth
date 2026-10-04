package com.company.userservice.config;

import com.company.userservice.user.dto.UserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RedisCacheSerializationTest {

    @Test
    void userResponseRoundTripsThroughJson() {
        var serializer=new Jackson2JsonRedisSerializer<>(new ObjectMapper(), UserResponse.class);
        var user=new UserResponse(UUID.randomUUID(),"jane@example.com","Jane","Doe",
            true,false,Set.of("USER"),Set.of("USER_READ","USER_WRITE"));

        assertEquals(user, serializer.deserialize(serializer.serialize(user)));
    }

    @Test
    void cachedPayloadHasNoTypeMetadata() {
        var serializer=new Jackson2JsonRedisSerializer<>(new ObjectMapper(), UserResponse.class);
        var json=new String(serializer.serialize(new UserResponse(UUID.randomUUID(),"a@b.co","A","B",
            true,true,Set.of(),Set.of())));

        assertFalse(json.contains("@class"), "payload must not carry polymorphic type info");
    }
}
