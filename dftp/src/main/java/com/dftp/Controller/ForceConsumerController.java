package com.dftp.Controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/force")
@RequiredArgsConstructor
public class ForceConsumerController {

    private final StringRedisTemplate redisTemplate;

    @PostMapping("/create-group")
    public String createGroup() {
        try {
            redisTemplate.opsForStream().createGroup("status-stream", "status-group");
            return "Group created successfully";
        } catch (Exception e) {
            return "Group creation failed or already exists: " + e.getMessage();
        }
    }

    @GetMapping("/group-info")
    public String getGroupInfo() {
        try {
            return redisTemplate.opsForStream().groups("status-stream").toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}