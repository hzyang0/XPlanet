package com.xplanet.booking;

import static com.xplanet.booking.Models.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
public class ApiController {
  private final AuthService auth;
  private final Store store;
  private final BookingService booking;
  private final Clock clock;
  private final OutboxDelivery delivery;
  private final MeterRegistry metrics;

  public ApiController(AuthService auth, Store store, BookingService booking, Clock clock,
      OutboxDelivery delivery, MeterRegistry metrics) {
    this.auth = auth;
    this.store = store;
    this.booking = booking;
    this.clock = clock;
    this.delivery = delivery;
    this.metrics = metrics;
  }

  @PostMapping("/auth/login")
  public Session login(@Valid @RequestBody Login input) {
    return auth.login(input);
  }

  @PostMapping("/auth/register")
  public Session register(@Valid @RequestBody Register input) {
    return auth.register(input);
  }

  @PostMapping("/auth/logout")
  public Map<String, Boolean> logout(@RequestHeader("Authorization") String authorization) {
    auth.logout(authorization.substring(7));
    return Map.of("ok", true);
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal Actor actor) {
    return Map.of(
        "user",
        actor,
        "serverTime",
        LocalDateTime.now(clock),
        "qualifications",
        store
            .jdbc()
            .queryForList(
                "SELECT equipment_id FROM qualification WHERE user_id=?", Long.class, actor.id()));
  }

  @GetMapping("/equipment")
  public List<Equipment> equipment(
      @RequestParam(defaultValue = "") @Size(max = 100) String q,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return store.equipmentList(q, limit, offset);
  }

  @GetMapping("/equipment/{id}/schedule")
  public List<Map<String, Object>> schedule(
      @PathVariable long id, @RequestParam LocalDateTime from, @RequestParam LocalDateTime to) {
    store.equipment(id, false);
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(7)) > 0)
      throw new Problem(400, "查询范围必须为 0～7 天");
    return store.rows(
        "SELECT slot_at FROM reservation_slot WHERE equipment_id=? AND slot_at>=? AND slot_at<?"
            + " ORDER BY slot_at",
        id,
        from,
        to);
  }

  @PostMapping("/equipment")
  public Equipment equipmentCreate(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody EquipmentInput input) {
    return booking.createEquipment(actor, input);
  }

  @PutMapping("/equipment/{id}/enabled")
  public Equipment enabled(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @Valid @RequestBody Enabled input) {
    return booking.setEnabled(actor, id, input);
  }

  @PutMapping("/equipment/{id}/training")
  public Map<String, Boolean> training(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @Valid @RequestBody Training input) {
    booking.training(actor, id, input);
    return Map.of("ok", true);
  }

  @GetMapping("/reservations")
  public List<Reservation> reservations(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return store.listReservations(actor, limit, offset);
  }

  @GetMapping("/reservations/{id}")
  public Reservation reservation(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    Reservation r = store.reservation(id);
    if (!actor.admin() && actor.id() != r.userId()) throw new Problem(403, "不能查看其他用户的预约");
    return r;
  }

  @PostMapping("/reservations")
  public Reservation create(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody CreateReservation input) {
    return booking.create(actor, input);
  }

  @PostMapping("/reservations/{id}/cancel")
  public Reservation cancel(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return booking.cancel(actor, id);
  }

  @PostMapping("/reservations/{id}/accept")
  public Reservation accept(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return booking.accept(actor, id);
  }

  @PostMapping("/reservations/{id}/decision")
  public Reservation decide(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @Valid @RequestBody Decision input) {
    return booking.decide(actor, id, input);
  }

  @PostMapping("/reservations/{id}/check-in")
  public Reservation checkIn(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return booking.checkIn(actor, id);
  }

  @PostMapping("/reservations/{id}/finish")
  public Reservation finish(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return booking.finish(actor, id);
  }

  @GetMapping("/reservations/{id}/audit")
  public List<Map<String, Object>> audit(
      @AuthenticationPrincipal Actor actor, @PathVariable long id) {
    reservation(actor, id);
    return store.rows(
        "SELECT from_status,to_status,actor_id,reason,created_at FROM reservation_audit WHERE"
            + " reservation_id=? ORDER BY id",
        id);
  }

  @GetMapping("/notifications")
  public List<Map<String, Object>> notifications(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return store.rows(
        "SELECT id,reservation_id,message,created_at,read_at FROM inbox_notification WHERE"
            + " user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
        actor.id(),
        limit,
        offset);
  }

  @PostMapping("/notifications/{id}/read")
  public Map<String, Boolean> read(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    int updated =
        store
            .jdbc()
            .update(
                "UPDATE inbox_notification SET read_at=? WHERE id=? AND user_id=?",
                LocalDateTime.now(clock),
                id,
                actor.id());
    if (updated == 0) throw new Problem(404, "通知不存在");
    return Map.of("ok", true);
  }

  @GetMapping("/admin/users")
  public List<Map<String, Object>> users(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "") @Size(max = 32) String q) {
    actor.requireAdmin();
    return store.rows(
        "SELECT id,username,role FROM app_user WHERE username LIKE ? ORDER BY id LIMIT 100",
        "%" + q + "%");
  }

  @GetMapping("/admin/summary")
  public Map<String, Object> summary(@AuthenticationPrincipal Actor actor) {
    actor.requireAdmin();
    return Map.of(
        "byStatus",
        store.rows("SELECT status,COUNT(*) AS total FROM reservation GROUP BY status"),
        "pendingEvents",
        store
            .jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE delivered_at IS NULL", Long.class),
        "activeSlots",
        store.jdbc().queryForObject("SELECT COUNT(*) FROM reservation_slot", Long.class));
  }

  @GetMapping("/admin/operations")
  public Map<String, Object> operations(@AuthenticationPrincipal Actor actor) {
    actor.requireAdmin();
    LocalDateTime oldest = store.jdbc().query("SELECT MIN(created_at) AS oldest_at FROM event_outbox"
        + " WHERE delivered_at IS NULL AND failed_at IS NULL", r -> r.next() ? Store.time(r, "oldest_at") : null);
    var timer = metrics.find("booking.maintenance.duration").timer();
    var failures = metrics.find("booking.maintenance.failures").counter();
    return Map.of(
        "failedEvents", store.jdbc().queryForObject("SELECT COUNT(*) FROM event_outbox WHERE failed_at IS NOT NULL", Long.class),
        "pendingEvents", store.jdbc().queryForObject("SELECT COUNT(*) FROM event_outbox WHERE delivered_at IS NULL AND failed_at IS NULL", Long.class),
        "oldestPendingSeconds", oldest == null ? 0 : Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).getSeconds()),
        "maintenanceRuns", timer == null ? 0 : timer.count(),
        "maintenanceLastMaxMs", timer == null ? 0 : timer.max(java.util.concurrent.TimeUnit.MILLISECONDS),
        "maintenanceFailures", failures == null ? 0 : failures.count());
  }

  @GetMapping("/admin/events")
  public List<Map<String, Object>> failedEvents(@AuthenticationPrincipal Actor actor) {
    actor.requireAdmin();
    return store.rows("SELECT id,reservation_id,retry_count,last_error,failed_at FROM event_outbox"
        + " WHERE failed_at IS NOT NULL ORDER BY id LIMIT 100");
  }

  @PostMapping("/admin/events/{id}/retry")
  public Map<String, Boolean> retryEvent(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    delivery.retry(actor, id);
    return Map.of("ok", true);
  }
}
