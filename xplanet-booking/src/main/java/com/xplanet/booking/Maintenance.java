package com.xplanet.booking;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "booking.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Maintenance {
  private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
  private final Store store;
  private final BookingService booking;
  private final OutboxDispatcher dispatcher;
  private final Clock clock;
  private final MeterRegistry metrics;

  public Maintenance(
      Store store,
      BookingService booking,
      OutboxDispatcher dispatcher,
      Clock clock,
      MeterRegistry metrics) {
    this.store = store;
    this.booking = booking;
    this.dispatcher = dispatcher;
    this.clock = clock;
    this.metrics = metrics;
    metrics.gauge(
        "booking.outbox.pending",
        store,
        s ->
            s.jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM event_outbox WHERE delivered_at IS NULL", Long.class));
  }

  @Scheduled(fixedDelayString = "${booking.scheduler-delay-ms:5000}")
  public void tick() {
    for (long id : store.equipmentIds())
      try {
        booking.sweep(id);
      } catch (Exception e) {
        metrics.counter("booking.maintenance.failures").increment();
        log.error("Equipment maintenance failed: {}", id, e);
      }
    try {
      metrics.counter("booking.notifications.delivered").increment(dispatcher.dispatch());
    } catch (Exception e) {
      metrics.counter("booking.outbox.failures").increment();
      log.error("Inbox dispatch failed", e);
    }
  }

  @Scheduled(fixedDelay = 3600000)
  public void cleanSessions() {
    store.jdbc().update("DELETE FROM auth_session WHERE expires_at<?", LocalDateTime.now(clock));
  }
}
