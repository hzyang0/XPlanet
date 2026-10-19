package com.xplanet.booking;

import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import org.springframework.stereotype.Service;

/**
 * Durable local inbox transport. Business + event are atomic; inbox + acknowledgement are atomic.
 */
@Service
public class OutboxDispatcher {
  private final Store store;
  private final Clock clock;
  private final OutboxDelivery delivery;
  private final MeterRegistry metrics;
  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

  public OutboxDispatcher(Store store, Clock clock, OutboxDelivery delivery, MeterRegistry metrics) {
    this.store = store;
    this.clock = clock;
    this.delivery = delivery;
    this.metrics = metrics;
  }

  public int dispatch() {
    List<Long> events = store.jdbc().queryForList(
        "SELECT id FROM event_outbox WHERE delivered_at IS NULL AND failed_at IS NULL"
            + " AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY id LIMIT 100",
        Long.class, LocalDateTime.now(clock));
    int delivered = 0;
    for (long id : events) {
      try {
        if (delivery.deliver(id)) delivered++;
      } catch (RuntimeException e) {
        metrics.counter("booking.outbox.failures").increment();
        log.warn("Notification delivery failed for event {} ({})", id, e.getClass().getSimpleName());
        delivery.recordFailure(id, e.getClass().getSimpleName());
      }
    }
    return delivered;
  }
}
