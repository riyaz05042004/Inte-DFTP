package com.example.status.service;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisConnectionChecker {

    private final StringRedisTemplate redisTemplate;

    public RedisConnectionChecker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void checkConnection() {
        try {
            String response = redisTemplate
                    .getConnectionFactory()
                    .getConnection()
                    .ping();

            System.out.println("Redis connected successfully. PING = " + response);

        } catch (Exception e) {
            System.err.println(" Redis connection failed: " + e.getMessage());
        }
    }
}
