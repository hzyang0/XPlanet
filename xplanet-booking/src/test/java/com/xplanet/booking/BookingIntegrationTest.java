package com.xplanet.booking;

import static com.xplanet.booking.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "booking.scheduler-enabled=false",
      "booking.seed-demo=false",
      "booking.admin-password=",
      "spring.datasource.hikari.maximum-pool-size=24"
    })
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.TimeConfig.class)
class BookingIntegrationTest {
  @Autowired BookingService booking;
  @Autowired Store store;
  @Autowired AuthService auth;
  @Autowired OutboxDispatcher outbox;
  @Autowired MutableClock clock;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  Actor owner, other, admin;
  long equipment;
  LocalDateTime start, end;
  private static final String HASH = new BCryptPasswordEncoder().encode("password123");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () ->
            System.getenv()
                .getOrDefault(
                    "TEST_DB_URL",
                    "jdbc:h2:mem:bookingtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"));
    r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "sa"));
    r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    final AtomicReference<Instant> instant = new AtomicReference<>();

    void at(LocalDateTime time) {
      instant.set(time.toInstant(ZoneOffset.UTC));
    }

    void advance(Duration d) {
      instant.updateAndGet(i -> i.plus(d));
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException();
      return this;
    }

    public Instant instant() {
      return instant.get();
    }
  }

  @BeforeEach
  void setup() {
    clock.at(LocalDateTime.of(2026, 10, 15, 0, 0));
    for (String table :
        List.of(
            "event_retry_audit",
            "inbox_notification",
            "event_outbox",
            "reservation_audit",
            "reservation_slot",
            "reservation",
            "qualification",
            "auth_session",
            "equipment",
            "app_user")) jdbc.update("DELETE FROM " + table);
    owner = user("owner", "USER");
    other = user("other", "USER");
    admin = user("admin", "ADMIN");
    equipment = resource(false, false);
    start = LocalDateTime.now(clock).plusHours(2);
    end = start.plusHours(2);
  }

  Actor user(String name, String role) {
    long id =
        store.insert(
            "INSERT INTO app_user(username,password_hash,role,created_at) VALUES(?,?,?,?)",
            name,
            HASH,
            role,
            LocalDateTime.now(clock));
    return new Actor(id, name, role);
  }

  long resource(boolean approval, boolean training) {
    return booking
        .createEquipment(admin, new EquipmentInput("示波器", "测量", "302", "测试资源", approval, training))
        .id();
  }

  CreateReservation input(long eq, LocalDateTime from, LocalDateTime to, String key) {
    return new CreateReservation(eq, from, to, "实验用途", key);
  }

  Reservation create(Actor actor) {
    return booking.create(actor, input(equipment, start, end, UUID.randomUUID().toString()));
  }

  void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(Problem.class)
        .extracting(e -> ((Problem) e).status())
        .isEqualTo(409);
  }

  void moveTo(LocalDateTime t) {
    clock.at(t);
  }

  List<Reservation> concurrent(List<Callable<Reservation>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      List<Future<Reservation>> results = pool.invokeAll(calls);
      List<Reservation> rows = new ArrayList<>();
      for (Future<Reservation> f : results) rows.add(f.get(30, TimeUnit.SECONDS));
      return rows;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void fiftyConcurrentOverlapsHaveExactlyOneHolder() throws Exception {
    List<Callable<Reservation>> calls = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      Actor u = user("user" + i, "USER");
      calls.add(() -> create(u));
    }
    List<Reservation> rows = concurrent(calls);
    assertThat(rows).filteredOn(r -> r.status() == Status.CONFIRMED).hasSize(1);
    assertThat(rows).filteredOn(r -> r.status() == Status.WAITLISTED).hasSize(49);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void adjacentIntervalsAreBothAvailable() {
    create(owner);
    Reservation r = booking.create(other, input(equipment, end, end.plusHours(1), "adjacent001"));
    assertThat(r.status()).isEqualTo(Status.CONFIRMED);
  }

  @Test
  void userQuotaIsAtomicAcrossDifferentEquipment() throws Exception {
    for (int i = 0; i < 49; i++)
      booking.create(owner, input(equipment, start.plusMinutes(i * 30L),
          start.plusMinutes((i + 1) * 30L), "quota-seed-" + i));
    List<Callable<Reservation>> calls = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      long eq = resource(false, false);
      String key = "quota-race-" + i;
      calls.add(() -> {
        try { return booking.create(owner, input(eq, start, end, key)); }
        catch (Problem p) { assertThat(p.status()).isEqualTo(409); return null; }
      });
    }
    assertThat(concurrent(calls).stream().filter(Objects::nonNull).count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE user_id=?",
        Integer.class, owner.id())).isEqualTo(50);
  }

  @Test
  void concurrentKeyReuseAcrossEquipmentReturnsBusinessConflict() throws Exception {
    List<Callable<Reservation>> calls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      long eq = resource(false, false);
      calls.add(() -> {
        try { return booking.create(owner, input(eq, start, end, "cross-equipment-key")); }
        catch (Problem p) { assertThat(p.status()).isEqualTo(409); return null; }
      });
    }
    assertThat(concurrent(calls).stream().filter(Objects::nonNull).count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isEqualTo(1);
  }

  @Test
  void httpErrorsAndScheduleBoundaryHaveCorrectStatus() throws Exception {
    String token = auth.login(new Login("owner", "password123")).token();
    mvc.perform(get("/api/equipment/" + equipment + "/schedule")
        .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/equipment/" + equipment + "/schedule")
        .param("from", start.toString()).param("to", start.plusDays(7).toString())
        .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    mvc.perform(get("/api/equipment/" + equipment + "/schedule")
        .param("from", start.toString()).param("to", start.plusDays(7).plusSeconds(1).toString())
        .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/no-such-resource").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/reservations").header("Authorization", "Bearer " + token))
        .andExpect(status().isMethodNotAllowed());
    mvc.perform(get("/favicon.svg")).andExpect(status().isOk());
    mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
  }

  @Test
  void partialOverlapIsWaitlisted() {
    create(owner);
    Reservation r =
        booking.create(
            other, input(equipment, start.plusMinutes(30), end.plusMinutes(30), "partial001"));
    assertThat(r.status()).isEqualTo(Status.WAITLISTED);
  }

  @Test
  void concurrentIdempotentRequestsProduceOneReservationAndOneEvent() throws Exception {
    List<Callable<Reservation>> calls = new ArrayList<>();
    for (int i = 0; i < 30; i++)
      calls.add(() -> booking.create(owner, input(equipment, start, end, "same-request-001")));
    assertThat(concurrent(calls).stream().map(Reservation::id).distinct().count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void idempotencyKeyCannotChangePayload() {
    booking.create(owner, input(equipment, start, end, "same-request-001"));
    conflict(
        () -> booking.create(owner, input(equipment, start, end.plusHours(1), "same-request-001")));
  }

  @Test
  void sameUserCannotQueueRepeatedlyForOverlappingTime() {
    create(owner);
    conflict(() -> create(owner));
  }

  @Test
  void cancellationOffersFifoAndAcceptIsIdempotent() {
    Reservation a = create(owner), b = create(other);
    Actor third = user("third", "USER");
    Reservation c = create(third);
    booking.cancel(owner, a.id());
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.OFFERED);
    assertThat(store.reservation(c.id()).status()).isEqualTo(Status.WAITLISTED);
    assertThat(booking.accept(other, b.id()).status()).isEqualTo(Status.CONFIRMED);
    assertThat(booking.accept(other, b.id()).status()).isEqualTo(Status.CONFIRMED);
    assertThat(booking.cancel(owner, a.id()).status()).isEqualTo(Status.CANCELLED);
  }

  @Test
  void offerExpiryAdvancesQueue() {
    Reservation a = create(owner), b = create(other);
    Actor third = user("third", "USER");
    Reservation c = create(third);
    booking.cancel(owner, a.id());
    clock.advance(Duration.ofMinutes(11));
    booking.sweep(equipment);
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.EXPIRED);
    assertThat(store.reservation(c.id()).status()).isEqualTo(Status.OFFERED);
  }

  @Test
  void overlappingOlderWaiterCannotBeBypassed() {
    booking.create(owner, input(equipment, start.plusHours(1), end.plusHours(1), "blocking001"));
    Reservation earlier = booking.create(other, input(equipment, start, end, "earlier001"));
    Actor third = user("third", "USER");
    Reservation later =
        booking.create(third, input(equipment, start, start.plusMinutes(30), "later001"));
    assertThat(earlier.status()).isEqualTo(Status.WAITLISTED);
    assertThat(later.status()).isEqualTo(Status.WAITLISTED);
    booking.sweep(equipment);
    assertThat(store.reservation(later.id()).status()).isEqualTo(Status.WAITLISTED);
  }

  @Test
  void unrelatedTimesAreNotBlockedByWaitlist() {
    create(owner);
    create(other);
    Actor third = user("third", "USER");
    assertThat(
            booking.create(third, input(equipment, end, end.plusHours(1), "unrelated1")).status())
        .isEqualTo(Status.CONFIRMED);
  }

  @Test
  void approvalHoldsSlotsAndRejectionPromotesWaiter() {
    equipment = resource(true, false);
    Reservation a = create(owner), b = create(other);
    assertThat(a.status()).isEqualTo(Status.PENDING_APPROVAL);
    booking.decide(admin, a.id(), new Decision(false, "用途不符"));
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.OFFERED);
    assertThat(booking.accept(other, b.id()).status()).isEqualTo(Status.PENDING_APPROVAL);
    assertThat(booking.decide(admin, b.id(), new Decision(true, "批准")).status())
        .isEqualTo(Status.CONFIRMED);
  }

  @Test
  void expiredApprovalCannotBeApprovedAndSlotsAreReclaimed() {
    equipment = resource(true, false);
    Reservation a = create(owner), b = create(other);
    clock.advance(Duration.ofMinutes(31));
    conflict(() -> booking.decide(admin, a.id(), new Decision(true, "批准")));
    booking.sweep(equipment);
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.EXPIRED);
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.OFFERED);
  }

  @Test
  void trainingIsRequiredAndRevocationCancelsAndPromotes() {
    equipment = resource(false, true);
    assertThatThrownBy(() -> create(owner)).isInstanceOf(Problem.class);
    booking.training(admin, equipment, new Training(owner.id(), true));
    booking.training(admin, equipment, new Training(other.id(), true));
    Reservation a = create(owner), b = create(other);
    booking.training(admin, equipment, new Training(owner.id(), false));
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.CANCELLED);
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.OFFERED);
  }

  @Test
  void stoppingEquipmentCancelsAllReservationsAndWaiters() {
    Reservation a = create(owner), b = create(other);
    Equipment e = store.equipment(equipment, false);
    booking.setEnabled(admin, equipment, new Enabled(false, e.version()));
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.CANCELLED);
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.CANCELLED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class))
        .isZero();
    conflict(() -> create(user("third", "USER")));
    booking.setEnabled(admin, equipment, new Enabled(true, e.version() + 1));
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.CANCELLED);
  }

  @Test
  void equipmentVersionPreventsStaleAdminWrite() {
    booking.setEnabled(admin, equipment, new Enabled(false, 0));
    conflict(() -> booking.setEnabled(admin, equipment, new Enabled(true, 0)));
  }

  @Test
  void onlyOwnerOrAdminMayMutateReservation() {
    Reservation a = create(owner);
    assertThatThrownBy(() -> booking.cancel(other, a.id()))
        .isInstanceOf(Problem.class)
        .extracting(e -> ((Problem) e).status())
        .isEqualTo(403);
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.CONFIRMED);
  }

  @Test
  void normalUserCannotApproveOrManageEquipment() {
    equipment = resource(true, false);
    Reservation a = create(owner);
    assertThatThrownBy(() -> booking.decide(owner, a.id(), new Decision(true, "approve")))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> booking.setEnabled(owner, equipment, new Enabled(false, 0)))
        .isInstanceOf(Problem.class);
  }

  @Test
  void checkInWindowAndFinishAreEnforced() {
    Reservation a = create(owner);
    conflict(() -> booking.checkIn(owner, a.id()));
    moveTo(start.minusMinutes(5));
    assertThat(booking.checkIn(owner, a.id()).status()).isEqualTo(Status.IN_USE);
    assertThat(booking.checkIn(owner, a.id()).status()).isEqualTo(Status.IN_USE);
    conflict(() -> booking.cancel(owner, a.id()));
    assertThat(booking.finish(owner, a.id()).status()).isEqualTo(Status.COMPLETED);
    assertThat(booking.finish(owner, a.id()).status()).isEqualTo(Status.COMPLETED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class))
        .isZero();
  }

  @Test
  void inUseEquipmentCannotBeStopped() {
    Reservation a = create(owner);
    moveTo(start);
    booking.checkIn(owner, a.id());
    conflict(() -> booking.setEnabled(admin, equipment, new Enabled(false, 0)));
  }

  @Test
  void noShowAndStaleWaitlistExpireWithoutLeakingSlots() {
    Reservation a = create(owner), b = create(other);
    moveTo(start.plusMinutes(15));
    booking.sweep(equipment);
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.NO_SHOW);
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.EXPIRED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class))
        .isZero();
  }

  @Test
  void inUseAutoCompletesAtEnd() {
    Reservation a = create(owner);
    moveTo(start);
    booking.checkIn(owner, a.id());
    moveTo(end);
    booking.sweep(equipment);
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.COMPLETED);
  }

  @Test
  void invalidTimeRangesLeaveNoRows() {
    for (CreateReservation r :
        List.of(
            input(equipment, start.plusMinutes(1), end, "badtime001"),
            input(equipment, start, start, "badtime002"),
            input(equipment, start, start.plusHours(9), "badtime003"),
            input(equipment, start.minusDays(2), end, "badtime004"),
            input(equipment, start, start.plusDays(31), "badtime005")))
      assertThatThrownBy(() -> booking.create(owner, r)).isInstanceOf(Problem.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isZero();
  }

  @Test
  void databaseConstraintRejectsDuplicateSlot() {
    Reservation a = create(owner);
    assertThatThrownBy(
            () ->
                jdbc.update("INSERT INTO reservation_slot VALUES(?,?,?)", equipment, start, a.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void twoSchedulersAndDispatchersAreIdempotent() throws Exception {
    Reservation a = create(owner), b = create(other);
    booking.cancel(owner, a.id());
    clock.advance(Duration.ofMinutes(11));
    ExecutorService p = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> fs = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        fs.add(
            p.submit(
                () -> {
                  booking.sweep(equipment);
                  outbox.dispatch();
                }));
      for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
    } finally {
      p.shutdownNow();
    }
    assertThat(store.reservation(b.id()).status()).isEqualTo(Status.EXPIRED);
    assertThat(outbox.dispatch()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_notification", Integer.class))
        .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_audit WHERE reservation_id=? AND"
                    + " to_status='EXPIRED'",
                Integer.class,
                b.id()))
        .isEqualTo(1);
  }

  @Test
  void transactionRollsBackReservationSlotsAuditAndEventTogether() {
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    s -> {
                      create(owner);
                      throw new IllegalStateException("injected failure before commit");
                    }))
        .isInstanceOf(IllegalStateException.class);
    for (String table :
        List.of("reservation", "reservation_slot", "reservation_audit", "event_outbox"))
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
  }

  @Test
  void tokensArePersistedHashedAndCanBeRevoked() {
    Session s = auth.login(new Login("owner", "password123"));
    assertThat(auth.resolve(s.token())).contains(owner);
    assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_session", String.class))
        .hasSize(64)
        .isNotEqualTo(s.token());
    auth.logout(s.token());
    assertThat(auth.resolve(s.token())).isEmpty();
  }

  @Test
  void poisonEventDoesNotBlockOtherNotificationsAndCanBeRetried() {
    create(owner);
    create(other);
    long eventId = jdbc.queryForObject("SELECT MIN(id) FROM event_outbox", Long.class);
    jdbc.update("UPDATE event_outbox SET message='poison' WHERE id=?", eventId);
    jdbc.execute("ALTER TABLE inbox_notification ADD CONSTRAINT test_reject_poison CHECK (message <> 'poison')");
    try {
      assertThat(outbox.dispatch()).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT retry_count FROM event_outbox WHERE id=?", Integer.class, eventId)).isEqualTo(1);
      assertThat(outbox.dispatch()).isZero();
      for (int i = 0; i < 4; i++) {
        clock.advance(Duration.ofMinutes(6));
        assertThat(outbox.dispatch()).isZero();
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE failed_at IS NOT NULL", Integer.class)).isEqualTo(1);
      clock.advance(Duration.ofHours(1));
      assertThat(outbox.dispatch()).isZero();
    } finally {
      jdbc.execute("ALTER TABLE inbox_notification DROP CONSTRAINT test_reject_poison");
    }
    assertThatThrownBy(() -> retryDelivery.retry(other, eventId)).isInstanceOf(Problem.class);
    retryDelivery.retry(admin, eventId);
    assertThat(outbox.dispatch()).isEqualTo(1);
    assertThat(outbox.dispatch()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_notification", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_retry_audit", Integer.class)).isEqualTo(1);
  }

  @Autowired OutboxDelivery retryDelivery;

  @Test
  void maintenanceCandidatesSkipIdleEquipmentAndUseCursor() {
    create(owner);
    create(other);
    long secondEquipment = resource(false, false);
    booking.create(owner, input(secondEquipment, start, end, "maintenance-holder"));
    booking.create(other, input(secondEquipment, start, end, "maintenance-waiter"));
    for (int i = 0; i < 20; i++) resource(false, false);
    assertThat(store.dueEquipment(0, 1, LocalDateTime.now(clock), 15)).containsExactly(equipment);
    assertThat(store.dueEquipment(equipment, 1, LocalDateTime.now(clock), 15)).containsExactly(secondEquipment);
    assertThat(store.dueEquipment(secondEquipment, 1, LocalDateTime.now(clock), 15)).isEmpty();
  }

  @Test
  void operationalEndpointsRequireAdmin() throws Exception {
    String userToken = auth.login(new Login("owner", "password123")).token();
    String adminToken = auth.login(new Login("admin", "password123")).token();
    mvc.perform(get("/api/admin/operations").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/operations").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.failedEvents").value(0));
  }

  @Test
  void expiredTokenIsRejected() {
    Session s = auth.login(new Login("owner", "password123"));
    clock.advance(Duration.ofHours(13));
    assertThat(auth.resolve(s.token())).isEmpty();
  }

  @Test
  void inUseTrainingCannotBeRevoked() {
    equipment = resource(false, true);
    booking.training(admin, equipment, new Training(owner.id(), true));
    Reservation a = create(owner);
    moveTo(start);
    booking.checkIn(owner, a.id());
    conflict(() -> booking.training(admin, equipment, new Training(owner.id(), false)));
    assertThat(store.trained(owner.id(), equipment)).isTrue();
  }

  @Test
  void inboxAndAcknowledgementRollbackTogether() {
    create(owner);
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    s -> {
                      outbox.dispatch();
                      throw new IllegalStateException("injected dispatcher failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_notification", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE delivered_at IS NULL", Integer.class))
        .isEqualTo(1);
    assertThat(outbox.dispatch()).isEqualTo(1);
    assertThat(outbox.dispatch()).isZero();
  }

  @Test
  void cancellationRacingApprovalCannotResurrectCancelledReservation() throws Exception {
    equipment = resource(true, false);
    Reservation a = create(owner);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> approval =
          pool.submit(
              () -> {
                try {
                  booking.decide(admin, a.id(), new Decision(true, "批准"));
                } catch (Problem expected) {
                  assertThat(expected.status()).isEqualTo(409);
                }
              });
      Future<?> cancel = pool.submit(() -> booking.cancel(owner, a.id()));
      approval.get(10, TimeUnit.SECONDS);
      cancel.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(store.reservation(a.id()).status()).isEqualTo(Status.CANCELLED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class))
        .isZero();
  }

  @Test
  void repeatedOfferCancellationDoesNotReleaseNextWaitersSlots() {
    Reservation a = create(owner), b = create(other);
    Actor third = user("third", "USER");
    Reservation c = create(third);
    booking.cancel(owner, a.id());
    booking.cancel(other, b.id());
    booking.cancel(other, b.id());
    assertThat(store.reservation(c.id()).status()).isEqualTo(Status.OFFERED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_slot WHERE reservation_id=?",
                Integer.class,
                c.id()))
        .isEqualTo(4);
  }

  @Test
  void replayStillReturnsOriginalAfterTimePasses() {
    CreateReservation in = input(equipment, start, end, "replay001");
    Reservation r = booking.create(owner, in);
    moveTo(end.plusHours(1));
    assertThat(booking.create(owner, in).id()).isEqualTo(r.id());
  }

  @Test
  void httpAuthenticationValidationAndOwnership() throws Exception {
    mvc.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
    String ownerToken = auth.login(new Login("owner", "password123")).token(),
        otherToken = auth.login(new Login("other", "password123")).token();
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.qualifications").isArray())
        .andExpect(jsonPath("$.serverTime").value("2026-10-15T00:00:00"));
    mvc.perform(
            post("/api/reservations")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    Reservation a = create(owner);
    mvc.perform(get("/api/reservations/" + a.id()).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/reservations?limit=10000").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/reservations").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void registrationCannotChooseAdminRole() throws Exception {
    String result =
        mvc.perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"newuser\",\"password\":\"password123\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(result).path("user").path("role").asText()).isEqualTo("USER");
  }

  @Test
  void scheduleDoesNotExposeOtherUserIdentity() throws Exception {
    create(owner);
    String token = auth.login(new Login("other", "password123")).token();
    String result =
        mvc.perform(
                get("/api/equipment/" + equipment + "/schedule")
                    .param("from", start.toString())
                    .param("to", end.toString())
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(result)).hasSize(4);
    assertThat(result).contains("2026-10-15T02:00:00");
    assertThat(result).doesNotContain("user_id", "reservation_id", "purpose");
  }
}
