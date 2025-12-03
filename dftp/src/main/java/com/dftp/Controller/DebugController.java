package com.dftp.Controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class DebugController {

    private final StringRedisTemplate redisTemplate;

    @GetMapping("/stream-info")
    public String getStreamInfo() {
        try {
            Long length = redisTemplate.opsForStream().size("status-stream");
            return "Stream length: " + length;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @PostMapping("/manual-message")
    public String sendManualMessage() {
        try {
            String message = "{\"fileId\":\"TEST123\",\"sourceservice\":\"trade-capture\",\"status\":\"received\"}";
            redisTemplate.opsForStream().add("status-stream", java.util.Map.of("payload", message));
            return "Manual message sent: " + message;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}