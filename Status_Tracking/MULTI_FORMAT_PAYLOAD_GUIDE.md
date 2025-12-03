# Status Tracking Service - Multi-Format Payload Support

## Overview

The Status Tracking service now supports flexible JSON payloads from multiple microservices with different data structures. The service intelligently extracts necessary fields and ignores unnecessary ones.

---

## Quick Start: How Other Services Push to Redis

1) Connect to Redis Sentinel (recommended)
```properties
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=sentinel1:26379,sentinel2:26379,sentinel3:26379
```

2) Add dependency (Java/Spring)
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

3) Send to the stream (`status-stream`) with a JSON payload in the `payload` field:
```java
// Java (Spring Boot)
@Autowired StringRedisTemplate redisTemplate;
ObjectMapper om = new ObjectMapper();

public void sendTradeCapture(String fileId, String status) throws Exception {
    String json = om.writeValueAsString(Map.of(
        "fileId", fileId,
        "sourceservice", "trade-capture",
        "status", status
    ));
    redisTemplate.opsForStream().add("status-stream", Map.of("payload", json));
}
```

```javascript
// Node.js (ioredis with Sentinel)
const Redis = require('ioredis');
const client = new Redis({
  name: 'mymaster',
  sentinels: [
    { host: 'sentinel1', port: 26379 },
    { host: 'sentinel2', port: 26379 },
    { host: 'sentinel3', port: 26379 },
  ],
});

async function sendOrderUpdate(fileId, orderId, firmId, status) {
  const payload = { fileId, order_id: orderId, firm_id: firmId, sourceservice: 'order-service', status };
  await client.xadd('status-stream', '*', 'payload', JSON.stringify(payload));
}
```

4) CLI example (for smoke tests)
```bash
docker exec redis-master redis-cli XADD status-stream "*" payload '{"fileId":"FILE001","sourceservice":"trade-capture","status":"PROCESSING"}'
```

ACK behavior: XADD returns a Redis stream ID (your producer-side confirmation of acceptance). The consumer ACKs after the row is written to PostgreSQL; if DB fails, the message stays pending and is retried.

---

## Supported Payload Formats

### Format 1: Trade-Capture Service
Files that are captured and tracked through the trade-capture microservice.

```json
{
  "fileId": "FILE001",
  "sourceservice": "trade-capture",
  "status": "PROCESSING"
}
```

**Required Fields:**
- `fileId` - Unique identifier for the file
- `sourceservice` - Must be "trade-capture"
- `status` - Current state (RECEIVED, PROCESSING, COMPLETED, etc.)

**Optional Fields:** None (extra fields are ignored)

---

### Format 2: Other Services (Order Service, etc.)
General-purpose format for other microservices that have orders, orders IDs, and firm/distributor information.

```json
{
  "fileId": "FILE002",
  "order_id": "ORDER123",
  "firm_id": "FIRM001",
  "sourceservice": "order-service",
  "status": "COMPLETED",
  "extra_field1": "ignored",
  "extra_field2": "also ignored"
}
```

**Required Fields:**
- `fileId` - Unique identifier for the file
- `order_id` - Order identifier
- `firm_id` - Firm/Distributor identifier (mapped to `distributorId` in DB)
- `sourceservice` - Source service name (any value except "trade-capture")
- `status` - Current state

**Optional Fields:**
- Any additional fields are safely ignored

---

## Database Schema

The `order_state_history` table stores records with the following columns:

```sql
CREATE TABLE order_state_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id VARCHAR(255),           -- File identifier
    order_id VARCHAR(255),          -- Order identifier (nullable)
    distributor_id VARCHAR(255),    -- Distributor/Firm ID (nullable)
    firm_id VARCHAR(255),           -- Firm ID (nullable)
    current_state VARCHAR(255) NOT NULL,    -- Status
    previous_state VARCHAR(255),    -- Previous state
    source_service VARCHAR(255),    -- Which service sent this
    event_time TIMESTAMP(6) NOT NULL        -- When it happened
);
```

---

## How It Works

### 1. Payload Reception
Messages are pushed to Redis Stream `status-stream`:
```bash
docker exec redis-master redis-cli XADD status-stream "*" payload '{...}'
```

### 2. Message Consumption
The `StatusStreamConsumer` service:
- Polls the stream every 5 seconds
- Reads up to 10 messages per batch
- Uses Consumer Groups for fault tolerance

### 3. Field Extraction
The `writeToDatabase()` method:
- Accepts multiple possible field names (case-sensitive)
- Extracts only necessary fields
- Ignores extra fields
- Validates required fields based on `sourceservice`

### 4. Data Storage
- Saves extracted fields to PostgreSQL
- Retrieves previous state from DB
- Sends ACK back to Redis stream (marks message as consumed)

### 5. Error Handling
- If required fields are missing: Message is **NOT ACK'ed** and remains in stream
- If DB write fails: Message is **NOT ACK'ed** for retry
- If message succeeds: Message is **ACK'ed** and removed from Consumer Group pending

---

## Service-Specific Field Mapping

### Trade-Capture Service Rules:
```
sourceservice = "trade-capture"
→ fileId is required
→ orderId and firmId are optional
→ Stores as: fileId only, orderId=null, distributorId=null
```

### Other Services Rules:
```
sourceservice != "trade-capture"
→ orderId is required
→ firmId is required (mapped to distributorId in DB)
→ fileId is optional
→ Stores as: fileId, orderId, distributorId=firmId, firmId=firmId
```

---

## Example Implementations

### Java/Spring Boot
```java
@Service
public class FileCapturePushService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    public void sendTradeCapture(String fileId, String status) {
        Map<String, Object> payload = Map.of(
            "fileId", fileId,
            "sourceservice", "trade-capture",
            "status", status
        );
        String jsonPayload = new ObjectMapper().writeValueAsString(payload);
        redisTemplate.opsForStream().add("status-stream", 
            Map.of("payload", jsonPayload));
    }
    
    public void sendOrderUpdate(String fileId, String orderId, String firmId, String status) {
        Map<String, Object> payload = Map.of(
            "fileId", fileId,
            "order_id", orderId,
            "firm_id", firmId,
            "sourceservice", "order-service",
            "status", status,
            "extra_metadata", "ignored"  // This will be ignored
        );
        String jsonPayload = new ObjectMapper().writeValueAsString(payload);
        redisTemplate.opsForStream().add("status-stream", 
            Map.of("payload", jsonPayload));
    }
}
```

### Node.js/Express
```javascript
const redis = require('ioredis');
const client = new redis({
    sentinels: [
        { host: 'sentinel1', port: 26379 },
        { host: 'sentinel2', port: 26379 },
        { host: 'sentinel3', port: 26379 }
    ],
    name: 'mymaster'
});

async function sendTradeCapture(fileId, status) {
    const payload = {
        fileId,
        sourceservice: 'trade-capture',
        status
    };
    await client.xadd('status-stream', '*', 'payload', JSON.stringify(payload));
}

async function sendOrderUpdate(fileId, orderId, firmId, status) {
    const payload = {
        fileId,
        order_id: orderId,
        firm_id: firmId,
        sourceservice: 'order-service',
        status,
        extra_metadata: 'ignored'
    };
    await client.xadd('status-stream', '*', 'payload', JSON.stringify(payload));
}
```

### Python
```python
import redis
import json

client = redis.Sentinel([
    ('sentinel1', 26379),
    ('sentinel2', 26379),
    ('sentinel3', 26379)
])
master = client.master_for('mymaster', socket_timeout=0.1)

def send_trade_capture(file_id, status):
    payload = {
        'fileId': file_id,
        'sourceservice': 'trade-capture',
        'status': status
    }
    master.xadd('status-stream', {'payload': json.dumps(payload)})

def send_order_update(file_id, order_id, firm_id, status):
    payload = {
        'fileId': file_id,
        'order_id': order_id,
        'firm_id': firm_id,
        'sourceservice': 'order-service',
        'status': status,
        'extra_metadata': 'ignored'
    }
    master.xadd('status-stream', {'payload': json.dumps(payload)})
```

---

## Testing

### Test Trade-Capture Format
```bash
docker exec redis-replica1 redis-cli XADD status-stream "*" payload \
  '{"fileId":"FILE001","sourceservice":"trade-capture","status":"PROCESSING"}'
```

Expected DB result:
```
id | file_id | order_id | distributor_id | status
---|---------|----------|----------------|----------
 1 | FILE001 | NULL     | NULL           | PROCESSING
```

### Test Other-Services Format
```bash
docker exec redis-replica1 redis-cli XADD status-stream "*" payload \
  '{"fileId":"FILE002","order_id":"ORDER123","firm_id":"FIRM001","sourceservice":"order-service","status":"COMPLETED"}'
```

Expected DB result:
```
id | file_id | order_id | distributor_id | status
---|---------|----------|----------------|----------
 2 | FILE002 | ORDER123 | FIRM001        | COMPLETED
```

---

## Verification Commands

### Check messages in stream:
```bash
docker exec redis-replica1 redis-cli XLEN status-stream
docker exec redis-replica1 redis-cli XREAD COUNT 10 STREAMS status-stream 0
```

### Check Consumer Group status:
```bash
docker exec redis-replica1 redis-cli XINFO GROUPS status-stream
docker exec redis-replica1 redis-cli XINFO CONSUMERS status-stream status-group
```

### Check DB records:
```bash
docker exec postgres psql -U postgres -d status_track -c \
  "SELECT * FROM order_state_history ORDER BY event_time DESC LIMIT 10;"
```

### View app logs:
```bash
docker logs status-tracking-app --tail 100
```

---

## Key Features

✅ **Flexible Field Mapping** - Multiple field names supported for same data (e.g., `firm_id`, `firmId`)  
✅ **Null Safety** - Gracefully handles missing optional fields  
✅ **Source Tracking** - Records which service sent each update  
✅ **State History** - Maintains previous state for audit trails  
✅ **Automatic ACK** - Messages are ACK'ed only after successful DB write  
✅ **Fault Tolerant** - Failed messages remain in stream for retry  
✅ **Extra Fields Ignored** - Unknown fields are safely discarded  

---

## Troubleshooting

### Message NOT ACK'ed
- Check app logs for error: `DB failed. Message NOT ACKED`
- Verify required fields are present in payload
- Check database connectivity

### Messages stuck in Consumer Group
- View pending messages: `docker exec redis-replica1 redis-cli XPENDING status-stream status-group`
- Check app is running and consuming

### Wrong field values stored
- Verify field names match expected format (case-sensitive)
- Check sourceservice value determines required fields

---

## Future Enhancements

- Add more service-specific formats as needed
- Implement dead-letter queue for permanently failed messages
- Add monitoring/alerting for stuck messages
- Create REST endpoint to check message status by messageId
