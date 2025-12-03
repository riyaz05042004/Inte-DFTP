# File Capture Microservice Integration with Status Service

## Current Flow Analysis

Your **Status Service** (`StatusStreamConsumer`) works like this:

```
File Capture MS (Sender)
    ↓
    PUSH message to Redis Stream "status-stream"
    ↓
Status Service (Consumer Group)
    ↓
    READ from stream using Consumer Group
    ↓
    WRITE to PostgreSQL (order_state_history table)
    ↓
    Send ACK back to Redis
    ↓
Message is removed from Consumer Group pending entries
```

---

## How File Capture MS Should Work

### Step 1: Capture File → Store in DB
```
File Capture MS:
  1. Receive file
  2. Parse and validate
  3. Store file metadata in DB
  4. Generate: orderId, distributorId, fundId, status
```

### Step 2: Push Status to Redis Stream
```
File Capture MS:
  1. Build message payload
  2. Send XADD to Redis stream "status-stream"
  3. Get messageId back from Redis
  4. Store messageId in DB for tracking
```

### Step 3: Status Service Processes & Sends ACK
```
Status Service Consumer:
  1. Reads from stream (reads 10 messages every 5 seconds)
  2. Processes message (saves to order_state_history)
  3. Sends ACK for that message ID
  4. Message moves to acknowledged state
```

### Step 4: File Capture Polls for ACK (Optional)
```
File Capture MS:
  1. Store message ID when pushing to stream
  2. Poll Redis to check if message was acknowledged
  3. Once ACK'ed, mark file as "processed"
  4. Continue to next process
```

---

## Implementation: File Capture Microservice

### Java/Spring Boot Implementation

#### 1. **Dependency (pom.xml)**
```xml
<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-redis</artifactId>
</dependency>
```

#### 2. **Configuration (application.properties)**
```properties
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=sentinel1:26379,sentinel2:26379,sentinel3:26379
spring.data.redis.timeout=10000

app.redis.stream=status-stream
app.redis.dlq-stream=status-dlq
```

#### 3. **File Capture Service with Redis Integration**

```java
// File: FileCapturePushService.java

package com.example.filecapture.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.example.filecapture.dao.FileCaptureDao;
import com.example.filecapture.entity.FileCaptureEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class FileCapturePushService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FileCaptureDao fileDao;

    @Value("${app.redis.stream}")
    private String streamName;

    @Value("${app.redis.dlq-stream}")
    private String dlqStream;

    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * STEP 1: File captured and stored in DB
     * STEP 2: Push status to Redis Stream
     * STEP 3: Store messageId in DB for tracking
     */
    public void captureAndPushStatus(FileCaptureEntity fileEntity) {
        try {
            // Step 1: Save file metadata to DB
            FileCaptureEntity saved = fileDao.save(fileEntity);
            System.out.println("✓ File saved to DB: " + saved.getId());

            // Step 2: Build payload for Redis stream
            Map<String, Object> payload = buildPayload(saved);

            // Step 3: Push to Redis stream
            RecordId messageId = pushToStream(payload);
            System.out.println("✓ Message pushed to stream: " + messageId);

            // Step 4: Store messageId in DB for ACK tracking
            saved.setRedisMessageId(messageId.getValue());
            saved.setMessageStatus("SENT_TO_REDIS");
            fileDao.save(saved);
            System.out.println("✓ MessageId stored in DB: " + messageId);

        } catch (Exception e) {
            System.err.println("✗ Error in file capture: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Build the exact payload format your Status Service expects
     */
    private Map<String, Object> buildPayload(FileCaptureEntity fileEntity) {
        Map<String, Object> payload = new HashMap<>();
        
        // Build inner payload object (this is what Status Service expects)
        Map<String, Object> innerPayload = new HashMap<>();
        innerPayload.put("distributorId", fileEntity.getDistributorId());
        innerPayload.put("fileid", fileEntity.getFundId());
        innerPayload.put("orderid", fileEntity.getOrderId());
        innerPayload.put("status", fileEntity.getStatus());  // e.g., "RECEIVED", "PROCESSING", "COMPLETED"
        innerPayload.put("sourceservice", "trade-capture");  // Your service name

        // Outer Redis message structure
        payload.put("payload", objectMapper.valueToTree(innerPayload));
        payload.put("timestamp", System.currentTimeMillis());
        
        return payload;
    }

    /**
     * Push message to Redis stream (Consumer Group will read this)
     */
    private RecordId pushToStream(Map<String, Object> payload) {
        return redisTemplate.opsForStream().add(
            streamName,
            payload
        );
    }

    /**
     * OPTIONAL: Check if message was acknowledged
     * Status Service sends ACK → Message is removed from pending entries
     */
    public boolean isMessageAcknowledged(String messageId) {
        try {
            // Check if message is in consumer group's pending entries
            var pendingMessages = redisTemplate.opsForStream()
                .pending(streamName, "status-group");
            
            if (pendingMessages == null || pendingMessages.size() == 0) {
                System.out.println("✓ No pending messages - Message " + messageId + " was ACK'ed!");
                return true;
            }

            // If message is not in pending, it was ACK'ed
            boolean isStillPending = pendingMessages.stream()
                .anyMatch(msg -> msg.getIdAsString().equals(messageId));

            if (!isStillPending) {
                System.out.println("✓ Message " + messageId + " is ACK'ed!");
                return true;
            } else {
                System.out.println("⏳ Message " + messageId + " still pending (Status Service not processed yet)");
                return false;
            }
        } catch (Exception e) {
            System.err.println("Error checking ACK status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Poll for ACK with timeout (useful for sync operations)
     */
    public boolean waitForAck(String messageId, long maxWaitMillis) {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            if (isMessageAcknowledged(messageId)) {
                return true;  // Got ACK!
            }
            
            try {
                Thread.sleep(500);  // Check every 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        System.err.println("✗ Timeout waiting for ACK on message: " + messageId);
        return false;  // Timeout - no ACK received
    }

    /**
     * If Status Service fails to process, message goes to DLQ
     */
    public void moveToDeadLetterQueue(String messageId, String reason) {
        try {
            Map<String, Object> dlqMessage = new HashMap<>();
            dlqMessage.put("failed_message_id", messageId);
            dlqMessage.put("reason", reason);
            dlqMessage.put("timestamp", System.currentTimeMillis());

            redisTemplate.opsForStream().add(dlqStream, dlqMessage);
            System.out.println("✗ Message moved to DLQ: " + messageId);
        } catch (Exception e) {
            System.err.println("Error moving to DLQ: " + e.getMessage());
        }
    }
}
```

#### 4. **File Capture Entity (DB Model)**

```java
// File: FileCaptureEntity.java

package com.example.filecapture.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "file_capture")
public class FileCaptureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "distributor_id", nullable = false)
    private String distributorId;

    @Column(name = "fund_id", nullable = false)
    private String fundId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "status", nullable = false)
    private String status;  // RECEIVED, PROCESSING, COMPLETED

    @Column(name = "redis_message_id")
    private String redisMessageId;  // Store Redis message ID for tracking

    @Column(name = "message_status")
    private String messageStatus;  // SENT_TO_REDIS, ACK_RECEIVED, FAILED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

#### 5. **Controller - REST API for File Upload**

```java
// File: FileController.java

package com.example.filecapture.controller;

import com.example.filecapture.entity.FileCaptureEntity;
import com.example.filecapture.service.FileCapturePushService;
import com.example.filecapture.dao.FileCaptureDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    @Autowired
    private FileCapturePushService capturePushService;

    @Autowired
    private FileCaptureDao fileDao;

    /**
     * Upload file → Save to DB → Push status to Redis
     */
    @PostMapping("/upload")
    public String uploadFile(
            @RequestParam MultipartFile file,
            @RequestParam String distributorId,
            @RequestParam String fundId,
            @RequestParam String orderId) {
        try {
            // Save file to disk/storage
            String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            String filePath = "/uploads/" + fileName;
            file.transferTo(new File(filePath));

            // Create entity
            FileCaptureEntity fileEntity = new FileCaptureEntity();
            fileEntity.setDistributorId(distributorId);
            fileEntity.setFundId(fundId);
            fileEntity.setOrderId(orderId);
            fileEntity.setFileName(fileName);
            fileEntity.setFilePath(filePath);
            fileEntity.setStatus("RECEIVED");

            // Step 1: Save to DB, Step 2: Push to Redis, Step 3: Store messageId
            capturePushService.captureAndPushStatus(fileEntity);

            return "File uploaded and status pushed to Redis";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Check if Status Service has ACK'ed the message
     */
    @GetMapping("/check-ack/{messageId}")
    public String checkAck(@PathVariable String messageId) {
        boolean isAcked = capturePushService.isMessageAcknowledged(messageId);
        return isAcked ? "✓ Message ACK'ed" : "⏳ Still processing";
    }

    /**
     * Wait for ACK with timeout
     */
    @PostMapping("/wait-ack/{messageId}")
    public String waitForAck(
            @PathVariable String messageId,
            @RequestParam(defaultValue = "5000") long timeoutMs) {
        boolean received = capturePushService.waitForAck(messageId, timeoutMs);
        return received ? "✓ ACK received" : "✗ Timeout - no ACK";
    }
}
```

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  FILE CAPTURE MS                                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Upload file                                                  │
│     ↓                                                             │
│  2. Save to DB (FileCaptureEntity)                               │
│     ↓                                                             │
│  3. Build payload with: distributorId, fundId, orderId, status   │
│     ↓                                                             │
│  4. Push to Redis Stream "status-stream"                         │
│     ↓                                                             │
│  5. Get messageId back                                           │
│     ↓                                                             │
│  6. Store messageId in DB                                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                          ↓ Redis Stream
                          │
┌─────────────────────────────────────────────────────────────────┐
│  STATUS SERVICE (Consumer)                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Read from "status-stream" (Consumer Group: "status-group")   │
│     ↓                                                             │
│  2. Parse payload (distributorId, fundId, orderId, status)       │
│     ↓                                                             │
│  3. Save to order_state_history table                            │
│     ↓                                                             │
│  4. Send ACK for messageId                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                          ↓ ACK sent
                          │
┌─────────────────────────────────────────────────────────────────┐
│  FILE CAPTURE MS (Optional polling)                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Poll Redis: Check if messageId is ACK'ed                     │
│     ↓                                                             │
│  2. If ACK'ed: Continue to next process                          │
│  3. If not ACK'ed: Retry or move to DLQ                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Points

### ✅ What Your Status Service Expects:

```json
{
  "payload": {
    "distributorId": "DIST001",
    "fileid": "FUND001",
    "orderid": "ORD123",
    "status": "RECEIVED",
    "sourceservice": "trade-capture"
  },
  "timestamp": 1701412345000
}
```

### ✅ How ACK Works:

1. **File Capture** pushes → Redis Stream
2. **Status Service** reads from stream (using Consumer Group)
3. **Status Service** processes & calls `acknowledge()` ✓
4. **Message removed** from Consumer Group pending entries
5. **File Capture** polls and sees it's no longer pending = ACK'ed ✓

### ✅ Error Handling:

- If Status Service fails to process: Message stays in pending
- If timeout: Move file record to DLQ status
- If 3+ retries fail: Move to Dead Letter Queue stream

---

## Docker Compose Setup

```yaml
services:
  file-capture-service:
    build:
      context: ../FileCapture
      dockerfile: Dockerfile
    container_name: file-capture-service
    environment:
      - SPRING_DATA_REDIS_SENTINEL_NODES=sentinel1:26379,sentinel2:26379,sentinel3:26379
      - SPRING_DATA_REDIS_SENTINEL_MASTER=mymaster
    ports:
      - "8083:8080"
    networks:
      - redinet
    depends_on:
      - postgres
      - sentinel1

networks:
  redinet:
    driver: bridge
```

---

## Quick Test

```bash
# 1. Upload a file
curl -X POST http://localhost:8083/api/files/upload \
  -F "file=@sample.txt" \
  -F "distributorId=DIST001" \
  -F "fundId=FUND001" \
  -F "orderId=ORD123"

# Response: messageId (e.g., "1701412345678-0")

# 2. Check if Status Service ACK'ed
curl http://localhost:8083/api/files/check-ack/1701412345678-0

# Response: "✓ Message ACK'ed" or "⏳ Still processing"

# 3. Wait for ACK (5 second timeout)
curl -X POST http://localhost:8083/api/files/wait-ack/1701412345678-0?timeoutMs=5000

# Response: "✓ ACK received" or "✗ Timeout - no ACK"

# 4. Verify in Status Service DB
SELECT * FROM order_state_history WHERE order_id='ORD123';
```

This is the complete flow for your file capture integration! 🚀
