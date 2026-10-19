package com.xplanet.booking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Durable local inbox transport. Business + event are atomic; inbox + acknowledgement are atomic.
 */
@Service
public class OutboxDispatcher {
  private final Store store;
  private final Clock clock;

  public OutboxDispatcher(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public int dispatch() {
    List<Map<String, Object>> events =
        store
            .jdbc()
            .queryForList(
                "SELECT * FROM event_outbox WHERE delivered_at IS NULL ORDER BY id LIMIT 100 FOR"
                    + " UPDATE");
    for (Map<String, Object> e : events) {
      long id = ((Number) e.get("id")).longValue();
      if (store
              .jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM inbox_notification WHERE event_id=?", Integer.class, id)
          == 0)
        store
            .jdbc()
            .update(
                "INSERT INTO inbox_notification(event_id,user_id,reservation_id,message,created_at)"
                    + " VALUES(?,?,?,?,?)",
                id,
                e.get("user_id"),
                e.get("reservation_id"),
                e.get("message"),
                LocalDateTime.now(clock));
      store
          .jdbc()
          .update(
              "UPDATE event_outbox SET delivered_at=? WHERE id=?", LocalDateTime.now(clock), id);
    }
    return events.size();
  }
}
