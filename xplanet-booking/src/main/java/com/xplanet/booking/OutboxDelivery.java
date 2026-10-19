package com.xplanet.booking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One event per transaction: the event lock, inbox write and acknowledgement share a commit. */
@Service
public class OutboxDelivery {
  private final Store store;
  private final Clock clock;

  public OutboxDelivery(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean deliver(long id) {
    LocalDateTime now = LocalDateTime.now(clock);
    List<Map<String, Object>> rows = store.rows(
        "SELECT * FROM event_outbox WHERE id=? AND delivered_at IS NULL AND failed_at IS NULL"
            + " AND (next_attempt_at IS NULL OR next_attempt_at<=?) FOR UPDATE", id, now);
    if (rows.isEmpty()) return false;
    Map<String, Object> event = rows.get(0);
    if (store.jdbc().queryForObject("SELECT COUNT(*) FROM inbox_notification WHERE event_id=?",
        Integer.class, id) == 0)
      store.jdbc().update(
          "INSERT INTO inbox_notification(event_id,user_id,reservation_id,message,created_at)"
              + " VALUES(?,?,?,?,?)", id, event.get("user_id"), event.get("reservation_id"),
          event.get("message"), now);
    store.jdbc().update("UPDATE event_outbox SET delivered_at=?,last_error=NULL WHERE id=?", now, id);
    return true;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void recordFailure(long id, String error) {
    List<Map<String, Object>> rows = store.rows(
        "SELECT retry_count FROM event_outbox WHERE id=? AND delivered_at IS NULL"
            + " AND failed_at IS NULL FOR UPDATE", id);
    if (rows.isEmpty()) return;
    int count = ((Number) rows.get(0).get("retry_count")).intValue() + 1;
    LocalDateTime now = LocalDateTime.now(clock);
    store.jdbc().update(
        "UPDATE event_outbox SET retry_count=?,next_attempt_at=?,last_error=?,failed_at=? WHERE id=?",
        count, now.plusSeconds(Math.min(300L, 1L << Math.min(count, 8))),
        error.substring(0, Math.min(300, error.length())), count >= 5 ? now : null, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void retry(Models.Actor actor, long id) {
    actor.requireAdmin();
    List<Map<String, Object>> rows = store.rows("SELECT delivered_at FROM event_outbox WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new Problem(404, "事件不存在");
    if (rows.get(0).get("delivered_at") != null) return;
    store.jdbc().update("UPDATE event_outbox SET retry_count=0,next_attempt_at=NULL,failed_at=NULL,last_error=NULL WHERE id=?", id);
    store.jdbc().update("INSERT INTO event_retry_audit(event_id,actor_id,created_at) VALUES(?,?,?)",
        id, actor.id(), LocalDateTime.now(clock));
  }
}
