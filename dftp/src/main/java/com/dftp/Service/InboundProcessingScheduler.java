package com.dftp.Service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InboundProcessingScheduler {

    private final SafeInboundQueue queue;
    private final OrderIngestService ingestService;

    @Scheduled(fixedDelay = 1000) 
    public void pollAndProcess() {

        String msg;
        while ((msg = queue.poll()) != null) {   
            ingestService.ingestFromMq(msg);     
        }
    }
}
