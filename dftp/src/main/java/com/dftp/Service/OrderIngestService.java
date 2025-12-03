
package com.dftp.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dftp.Entity.OutboxEventEntity;
import com.dftp.Entity.RawOrderEntity;
import com.dftp.Repository.OutboxEventRepository;
import com.dftp.Repository.RawOrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIngestService {

    private final RawOrderRepository rawOrderRepository;
    private final OutboxEventRepository outboxRepository;
    private final SafeInboundQueue safeQueue;
    private final StatusTrackingService statusTrackingService;


    @Transactional
    public void ingestFromMq(String payload) {

        String checksum = generateChecksum(payload.getBytes());
        checkDuplicate(checksum);

        RawOrderEntity raw = RawOrderEntity.builder()
//                .id(UUID.fromString(String.valueOf(UUID.fromString(UUID.randomUUID().toString()))))
                .id(UUID.randomUUID())
                .source("MQ")
                .payload(payload)              
                .checksum(checksum)
                .receivedAt(Instant.now())
                .build();

        rawOrderRepository.save(raw);
        createOutboxEvent(raw);
    }


    @Transactional
public void ingestFromSqs(String payload) {

    String checksum = generateChecksum(payload.getBytes());
    checkDuplicate(checksum);

    RawOrderEntity raw = RawOrderEntity.builder()
            .id(UUID.randomUUID())
            .source("S3")     
            .payload(payload)
            .checksum(checksum)
            .receivedAt(Instant.now())
            .build();

    rawOrderRepository.save(raw);
    createOutboxEvent(raw);
}

    
    private void createOutboxEvent(RawOrderEntity raw) {

        OutboxEventEntity evt = OutboxEventEntity.builder()
//                .id(UUID.fromString(String.valueOf(UUID.fromString(UUID.randomUUID().toString()))))
                .id(UUID.randomUUID())
                .rawOrderId(raw.getId())
                .source(raw.getSource())
                .eventType("OrderReceivedEvent")
                .payload(raw.getPayload())   
                .status("NEW")
                .createdAt(Instant.now())
                .build();

        outboxRepository.save(evt);
        
        try {
            // Send message to Redis cache
            String redisId = statusTrackingService.sendStatusMessage(
                raw.getId(), 
                raw.getSource(), 
                "received"
            );
            
            // Update status to PENDING after successful Redis send
            evt.setStatus("PENDING");
            outboxRepository.save(evt);
            
            log.info("Updated outbox status to PENDING for rawOrderId: {} with Redis ID: {}", 
                raw.getId(), redisId);
                
        } catch (Exception e) {
            log.error("Failed to send status to Redis for rawOrderId: {}", raw.getId(), e);
            // Keep status as NEW if Redis send fails
        }
    }


    private void checkDuplicate(String checksum) {
        rawOrderRepository.findByChecksum(checksum).ifPresent(x -> {
            throw new RuntimeException("Duplicate data. Checksum already exists.");
        });
    }


    private String generateChecksum(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Checksum generation failed", e);
        }
    }


    private String detectFileType(String filename) {
        if (filename == null) return "UNKNOWN";
        String f = filename.toLowerCase();
        if (f.endsWith(".csv")) return "CSV_FILE";
        if (f.endsWith(".json")) return "JSON_FILE";
        if (f.endsWith(".xml")) return "XML_FILE";
        return "FILE";
    }


    @Transactional
    public void processInboundMessages() {
        String msg;
        while ((msg = safeQueue.poll()) != null) {
            ingestFromMq(msg);
        }
    }
}


