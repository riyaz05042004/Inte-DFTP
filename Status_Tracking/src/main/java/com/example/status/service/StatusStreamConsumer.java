package com.example.status.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.status.dao.OrderStateHistoryDao;
import com.example.status.entity.OrderStateHistoryEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Range;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.connection.stream.StreamRecords;

@Service
public class StatusStreamConsumer {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int MAX_DELIVERY_ATTEMPTS = 5;

    @Value("${app.redis.stream}")
    private String STREAM_KEY;

    @Value("${app.redis.dlq-stream}")
    private String DLQ_STREAM;

    @Value("${app.redis.group}")
    private String GROUP_NAME;

    @Value("${app.redis.consumer}")
    private String CONSUMER_NAME;

    @Autowired
    private OrderStateHistoryDao historyDao;

    @PostConstruct
    public void init() {
        System.out.println("Initializing StatusStreamConsumer...");
        Thread t = new Thread(() -> {
            try {
                System.out.println("Consumer thread starting, waiting 2 seconds...");
                Thread.sleep(2000);
                System.out.println("Starting to consume from stream: " + STREAM_KEY);
                startConsuming();
            } catch (InterruptedException e) {
                System.err.println("Consumer thread interrupted");
                Thread.currentThread().interrupt();
            }
        }, "status-stream-consumer");
        t.setDaemon(true);
        t.start();
        System.out.println("Consumer thread started");
    }

    private void startConsuming() {
        System.out.println("Consumer loop started for stream: " + STREAM_KEY + ", group: " + GROUP_NAME);
        while (true) {
            try {
                try {
                    redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP_NAME);
                    System.out.println("Consumer group created or already exists");
                } catch (Exception e) {
                    System.out.println("Group creation skipped: " + e.getMessage());
                }
                reclaimPending(Duration.ofSeconds(30), 50);
                StreamOffset<String> offset = StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed());
                List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(5)),
                        offset);

                if (messages != null && !messages.isEmpty()) {
                    for (MapRecord<String, Object, Object> message : messages) {
                        handleWithAck(message);
                    }
                }

            } catch (Exception e) {
                System.err.println("Error while reading stream: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleWithAck(MapRecord<String, Object, Object> message) {
        String recordId = message.getId().getValue();
        Map<Object, Object> data = message.getValue();
        try {
            boolean processed = writeToDatabase(recordId, data);
            if (processed) {
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, message.getId());
                System.out.println("ACK SENT for " + recordId);
                redisTemplate.opsForStream().delete(STREAM_KEY, message.getId());
            } else {
                System.err.println("Processing failed, leaving pending for retry: " + recordId);
            }

        } catch (Exception e) {
            System.err.println("DB failed. Message NOT ACKED: " + recordId);
            e.printStackTrace();
        }
    }

    private void reclaimPending(Duration minIdle, int batchSize) {
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP_NAME, Range.unbounded(),
                    batchSize);
            if (pending == null || pending.isEmpty()) {
                return;
            }

            List<RecordId> toClaim = new ArrayList<>();
            for (PendingMessage pm : pending) {
                long deliveries = pm.getTotalDeliveryCount();
                if (deliveries >= MAX_DELIVERY_ATTEMPTS) {
                    moveToDlq(pm.getId(), deliveries, "Exceeded max retry attempts");
                    continue;
                }
                if (pm.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                    toClaim.add(pm.getId());
                }
            }

            if (toClaim.isEmpty()) {
                return;
            }

            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                    STREAM_KEY, GROUP_NAME, CONSUMER_NAME, minIdle, toClaim.toArray(new RecordId[0]));

            if (claimed != null) {
                for (MapRecord<String, Object, Object> record : claimed) {
                    handleWithAck(record);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to reclaim pending messages: " + e.getMessage());
        }
    }

    private void moveToDlq(RecordId recordId, long attempts, String reason) {
        try {
            List<MapRecord<String, Object, Object>> original = redisTemplate.opsForStream().range(
                    STREAM_KEY,
                    Range.closed(recordId.getValue(), recordId.getValue()));

            Map<String, Object> dlqPayload = new java.util.HashMap<>();
            dlqPayload.put("failed_record_id", recordId.getValue());
            dlqPayload.put("reason", reason);
            dlqPayload.put("attempts", String.valueOf(attempts));

            if (original != null && !original.isEmpty()) {
                dlqPayload.put("stream_payload", toJson(original.get(0).getValue()));
            }

            redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(DLQ_STREAM)
                    .ofMap(dlqPayload));

            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, recordId);
            redisTemplate.opsForStream().delete(STREAM_KEY, recordId);
            System.err.println("Moved record to DLQ after " + attempts + " attempts: " + recordId.getValue());
        } catch (Exception e) {
            System.err.println("Failed to move record to DLQ: " + recordId.getValue() + " because " + e.getMessage());
        }
    }

    private String toJson(Map<Object, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }

    private boolean writeToDatabase(String recordId, Map<Object, Object> data) throws Exception {
        Map<String, Object> payload = parsePayload(data.get("payload"));
        if (payload == null) {
            System.err.println("Payload missing or invalid for record: " + recordId);
            return false;
        }

        String sourceService = getStringValue(payload, "sourceservice", "source_service", "sourceService");
        String status = getStringValue(payload, "status");
        String fileId = getStringValue(payload, "fileId", "files_id", "file_id");
        String orderId = getStringValue(payload, "orderId", "order_id");
        Integer distributorId = getIntValue(payload, "distributorId", "distributor_id", "firmId", "firm_id");

        if (status == null || sourceService == null) {
            System.err.println("Missing status/sourceService for record: " + recordId);
            return false;
        }

        boolean isTradeCapture = "trade-capture".equalsIgnoreCase(sourceService);
        validateIdentifiers(recordId, sourceService, isTradeCapture, fileId, orderId, distributorId);

        LocalDateTime eventTime = extractEventTime(recordId);
        String previousState = findPreviousState(fileId, orderId, distributorId);

        OrderStateHistoryEntity entity = new OrderStateHistoryEntity();
        entity.setFileId(fileId);
        entity.setOrderId(orderId);
        entity.setDistributorId(distributorId);
        entity.setPreviousState(previousState);
        entity.setCurrentState(status);
        entity.setSourceService(sourceService);
        entity.setEventTime(eventTime);

        historyDao.save(entity);

        System.out.println("Saved to DB: fileId=" + fileId +
                " orderId=" + orderId +
                " [distributor=" + distributorId + "] : " +
                previousState + " -> " + status +
                " (source: " + sourceService + ")");

        return true;
    }

    private Map<String, Object> parsePayload(Object payloadObj) {
        if (payloadObj == null) {
            return null;
        }
        if (payloadObj instanceof Map) {
            return new java.util.HashMap<>((Map<String, Object>) payloadObj);
        }
        String raw = payloadObj.toString();
        try {
            return new ObjectMapper().readValue(raw, Map.class);
        } catch (Exception ex) {
            System.err.println("Failed to parse payload: " + raw);
            return null;
        }
    }

    private void validateIdentifiers(String recordId, String sourceService, boolean isTradeCapture,
            String fileId, String orderId, Integer distributorId) {
        if (isTradeCapture) {
            if (fileId == null && orderId == null) {
                throw new IllegalStateException("trade-capture requires orderId or fileId (" + recordId + ")");
            }
            return;
        }

        if (orderId == null || distributorId == null) {
            throw new IllegalStateException("orderId and distributorId are required for service "
                    + sourceService + " (" + recordId + ")");
        }

        if (fileId == null) {
            if (historyDao.findTopByOrderIdOrderByEventTimeDesc(orderId).isEmpty()) {
                throw new IllegalStateException("orderId " + orderId + " not seen yet from trade-capture ("
                        + recordId + ")");
            }
        } else if (!historyDao.existsByFileId(fileId)) {
            throw new IllegalStateException("fileId " + fileId + " not found yet for order: " + orderId
                    + " (" + recordId + ")");
        }
    }

    private LocalDateTime extractEventTime(String recordId) {
        String timestampPart = recordId.split("-")[0];
        long timestampMillis = Long.parseLong(timestampPart);
        return Instant.ofEpochMilli(timestampMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private String findPreviousState(String fileId, String orderId, Integer distributorId) {
        Optional<OrderStateHistoryEntity> lastRecord = Optional.empty();
        if (fileId != null) {
            lastRecord = historyDao.findTopByFileIdOrderByEventTimeDesc(fileId);
        } else if (orderId != null && distributorId != null) {
            lastRecord = historyDao.findTopByOrderIdAndDistributorIdOrderByEventTimeDesc(orderId, distributorId);
            if (lastRecord.isEmpty()) {
                lastRecord = historyDao.findTopByOrderIdOrderByEventTimeDesc(orderId);
            }
        } else if (orderId != null) {
            lastRecord = historyDao.findTopByOrderIdOrderByEventTimeDesc(orderId);
        }
        return lastRecord.map(OrderStateHistoryEntity::getCurrentState).orElse(null);
    }

    private String getStringValue(Map<String, Object> map, String... fieldNames) {
        if (map == null || fieldNames == null || fieldNames.length == 0) {
            return null;
        }

        for (String fieldName : fieldNames) {
            Object value = map.get(fieldName);
            if (value != null) {
                String strValue = String.valueOf(value).trim();
                if (!strValue.isEmpty() && !"null".equalsIgnoreCase(strValue)) {
                    return strValue;
                }
            }
        }
        return null;
    }

    private Integer getIntValue(Map<String, Object> map, String... fieldNames) {
        String value = getStringValue(map, fieldNames);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            System.err.println("Failed to parse integer from value '" + value + "'");
            return null;
        }
    }

}
