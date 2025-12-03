package com.dftp.Controller;

import com.dftp.Service.StatusTrackingService;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.UUID;

@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {

    private final StatusTrackingService statusTrackingService;

    @PostMapping("/redis")
    public String testRedis(@RequestParam String source) {
        try {
            UUID testId = UUID.randomUUID();
            String redisId = statusTrackingService.sendStatusMessage(testId, source, "received");
            return "Success! Redis ID: " + redisId + " for " + source + " with ID: " + testId;
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed: " + e.getMessage() + " - Cause: " + (e.getCause() != null ? e.getCause().getMessage() : "Unknown");
        }
    }
}