package com.xplanet.article.projection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeCountProjectionJob {

    private final LikeCountProjectionService projectionService;

    @Value("${like.projection.batch-size:1000}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${like.projection.interval-ms:500}")
    public void flush() {
        try {
            int processed = projectionService.flushBatch(batchSize);
            if (processed > 0) {
                log.info("like count projection flushed, events={}", processed);
            }
        } catch (Exception e) {
            log.error("like count projection failed", e);
        }
    }
}
