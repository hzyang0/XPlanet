package com.xplanet.booking;

import static com.xplanet.booking.Models.*;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * All resource mutations acquire the equipment row first. Slots are the final DB exclusion
 * constraint.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BookingService {
  private final Store store;
  private final Clock clock;
  private final int offerMinutes, approvalMinutes, noShowMinutes, earlyMinutes, horizonDays;

  public BookingService(
      Store store,
      Clock clock,
      @Value("${booking.offer-minutes:10}") int offerMinutes,
      @Value("${booking.approval-minutes:30}") int approvalMinutes,
      @Value("${booking.no-show-minutes:15}") int noShowMinutes,
      @Value("${booking.check-in-early-minutes:10}") int earlyMinutes,
      @Value("${booking.horizon-days:30}") int horizonDays) {
    this.store = store;
    this.clock = clock;
    this.offerMinutes = offerMinutes;
    this.approvalMinutes = approvalMinutes;
    this.noShowMinutes = noShowMinutes;
    this.earlyMinutes = earlyMinutes;
    this.horizonDays = horizonDays;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  public Reservation create(Actor actor, CreateReservation input) {
    Equipment e = store.equipment(input.equipmentId(), true);
    Optional<Reservation> replay = store.byKey(actor.id(), input.requestKey());
    if (replay.isPresent()) {
      Reservation r = replay.get();
      if (r.equipmentId() != input.equipmentId()
          || !r.startAt().equals(input.startAt())
          || !r.endAt().equals(input.endAt())
          || !r.purpose().equals(input.purpose().trim())) throw Problem.conflict("同一请求标识不能用于不同预约");
      return r;
    }
    LocalDateTime now = now();
    validateTimes(input.startAt(), input.endAt(), now);
    if (!e.enabled()) throw Problem.conflict("设备已停用");
    if (e.trainingRequired() && !store.trained(actor.id(), e.id()))
      throw new Problem(403, "请先获得本设备的培训资格");
    maintain(e, now);
    if (store.active(e.id()).stream()
        .anyMatch(r -> r.userId() == actor.id() && overlap(r, input.startAt(), input.endAt())))
      throw Problem.conflict("你在该设备已有重叠的预约或候补，请先处理原预约");
    if (store
            .jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE user_id=? AND status IN"
                    + " ('WAITLISTED','OFFERED','PENDING_APPROVAL','CONFIRMED','IN_USE')",
                Integer.class,
                actor.id())
        >= 50) throw Problem.conflict("未完成预约已达 50 条，请先处理已有预约");
    boolean olderWaiting =
        store.active(e.id()).stream()
            .anyMatch(
                r -> r.status() == Status.WAITLISTED && overlap(r, input.startAt(), input.endAt()));
    Status status =
        (!olderWaiting && store.slotsFree(e.id(), input.startAt(), input.endAt()))
            ? (e.approvalRequired() ? Status.PENDING_APPROVAL : Status.CONFIRMED)
            : Status.WAITLISTED;
    LocalDateTime deadline =
        status == Status.PENDING_APPROVAL
            ? min(now.plusMinutes(approvalMinutes), input.startAt())
            : null;
    long id =
        store.insert(
            "INSERT INTO"
                + " reservation(equipment_id,user_id,request_key,start_at,end_at,purpose,status,deadline,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
            e.id(),
            actor.id(),
            input.requestKey(),
            input.startAt(),
            input.endAt(),
            input.purpose().trim(),
            status.name(),
            deadline,
            now,
            now);
    Reservation r = store.reservation(id);
    if (status.holdsSlots()) store.allocate(r);
    store.audit(
        r, null, actor.id(), status == Status.WAITLISTED ? "时段被占用，进入先进先出候补队列" : "提交预约", now);
    return r;
  }

  public Reservation cancel(Actor actor, long id) {
    Reservation r = lockOwned(actor, id);
    Equipment e = store.equipment(r.equipmentId(), false);
    LocalDateTime now = now();
    if (r.status() == Status.CANCELLED) return r;
    if (r.status().terminal() || r.status() == Status.IN_USE) throw Problem.conflict("当前状态不能取消");
    if (!r.startAt().isAfter(now)) throw Problem.conflict("开始时间已到，不能取消");
    change(r, Status.CANCELLED, null, actor.id(), "取消预约", now);
    maintain(e, now);
    return store.reservation(id);
  }

  public Reservation accept(Actor actor, long id) {
    Reservation r = lockOwned(actor, id);
    Equipment e = store.equipment(r.equipmentId(), false);
    LocalDateTime now = now();
    if (r.status() == Status.CONFIRMED || r.status() == Status.PENDING_APPROVAL) return r;
    require(r, Status.OFFERED);
    if (!r.deadline().isAfter(now) || !r.startAt().isAfter(now)) throw Problem.conflict("候补确认已过期");
    if (!e.enabled()) throw Problem.conflict("设备已停用");
    if (e.trainingRequired() && !store.trained(r.userId(), e.id()))
      throw new Problem(403, "培训资格已失效");
    Status next = e.approvalRequired() ? Status.PENDING_APPROVAL : Status.CONFIRMED;
    return change(
        r,
        next,
        next == Status.PENDING_APPROVAL ? min(now.plusMinutes(approvalMinutes), r.startAt()) : null,
        actor.id(),
        "确认候补名额",
        now);
  }

  public Reservation decide(Actor actor, long id, Decision decision) {
    actor.requireAdmin();
    Reservation r = lock(id);
    LocalDateTime now = now();
    require(r, Status.PENDING_APPROVAL);
    if (!r.deadline().isAfter(now)) throw Problem.conflict("审批期限已过");
    Reservation result =
        change(
            r,
            decision.approve() ? Status.CONFIRMED : Status.REJECTED,
            null,
            actor.id(),
            decision.reason(),
            now);
    if (!decision.approve()) maintain(store.equipment(r.equipmentId(), false), now);
    return result;
  }

  public Reservation checkIn(Actor actor, long id) {
    Reservation r = lockOwned(actor, id);
    LocalDateTime now = now();
    if (r.status() == Status.IN_USE) return r;
    require(r, Status.CONFIRMED);
    if (now.isBefore(r.startAt().minusMinutes(earlyMinutes))
        || !now.isBefore(r.startAt().plusMinutes(noShowMinutes))
        || !now.isBefore(r.endAt()))
      throw Problem.conflict("请在开始前 " + earlyMinutes + " 分钟至开始后 " + noShowMinutes + " 分钟内签到");
    if (store.active(r.equipmentId()).stream()
        .anyMatch(x -> x.id() != r.id() && x.status() == Status.IN_USE))
      throw Problem.conflict("上一预约仍在使用，请稍后签到");
    return change(r, Status.IN_USE, null, actor.id(), "签到使用", now);
  }

  public Reservation finish(Actor actor, long id) {
    Reservation r = lockOwned(actor, id);
    LocalDateTime now = now();
    if (r.status() == Status.COMPLETED) return r;
    require(r, Status.IN_USE);
    Reservation result = change(r, Status.COMPLETED, null, actor.id(), "结束使用", now);
    maintain(store.equipment(r.equipmentId(), false), now);
    return result;
  }

  public Equipment createEquipment(Actor actor, EquipmentInput input) {
    actor.requireAdmin();
    long id =
        store.insert(
            "INSERT INTO"
                + " equipment(name,category,location,description,approval_required,training_required)"
                + " VALUES(?,?,?,?,?,?)",
            input.name().trim(),
            input.category().trim(),
            input.location().trim(),
            input.description().trim(),
            input.approvalRequired(),
            input.trainingRequired());
    return store.equipment(id, false);
  }

  public Equipment setEnabled(Actor actor, long id, Enabled input) {
    actor.requireAdmin();
    Equipment e = store.equipment(id, true);
    if (e.version() != input.expectedVersion()) throw Problem.conflict("设备已被其他管理员修改，请刷新");
    if (!input.enabled() && store.active(id).stream().anyMatch(r -> r.status() == Status.IN_USE))
      throw Problem.conflict("设备正在使用，请先结束使用再停用");
    store
        .jdbc()
        .update("UPDATE equipment SET enabled=?,version=version+1 WHERE id=?", input.enabled(), id);
    if (!input.enabled())
      for (Reservation r : store.active(id))
        change(r, Status.CANCELLED, null, actor.id(), "设备停用，预约取消", now());
    return store.equipment(id, false);
  }

  public void training(Actor actor, long equipmentId, Training input) {
    actor.requireAdmin();
    store.equipment(equipmentId, true);
    if (store
            .jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, input.userId())
        == 0) throw new Problem(404, "用户不存在");
    if (input.granted()) {
      if (!store.trained(input.userId(), equipmentId))
        store
            .jdbc()
            .update(
                "INSERT INTO qualification(user_id,equipment_id,granted_at) VALUES(?,?,?)",
                input.userId(),
                equipmentId,
                now());
    } else {
      List<Reservation> affected =
          store.active(equipmentId).stream().filter(r -> r.userId() == input.userId()).toList();
      if (affected.stream().anyMatch(r -> r.status() == Status.IN_USE))
        throw Problem.conflict("用户正在使用，暂不能撤销资格");
      store
          .jdbc()
          .update(
              "DELETE FROM qualification WHERE user_id=? AND equipment_id=?",
              input.userId(),
              equipmentId);
      if (store.equipment(equipmentId, false).trainingRequired())
        for (Reservation r : affected)
          change(r, Status.CANCELLED, null, actor.id(), "培训资格撤销", now());
      maintain(store.equipment(equipmentId, false), now());
    }
  }

  /** Called per equipment in independent transactions; no JVM-only distributed lock. */
  public void sweep(long equipmentId) {
    maintain(store.equipment(equipmentId, true), now());
  }

  private void maintain(Equipment e, LocalDateTime now) {
    for (Reservation r : store.active(e.id())) {
      if ((r.status() == Status.OFFERED || r.status() == Status.PENDING_APPROVAL)
          && !r.deadline().isAfter(now)) change(r, Status.EXPIRED, null, null, "限时确认或审批超时", now);
      else if (r.status() == Status.WAITLISTED && !r.startAt().isAfter(now))
        change(r, Status.EXPIRED, null, null, "预约时间已到，候补结束", now);
      else if (r.status() == Status.CONFIRMED
          && !r.startAt().plusMinutes(noShowMinutes).isAfter(now))
        change(r, Status.NO_SHOW, null, null, "未在签到期限内签到", now);
      else if (r.status() == Status.IN_USE && !r.endAt().isAfter(now))
        change(r, Status.COMPLETED, null, null, "预约时段结束，自动完成", now);
    }
    if (!e.enabled()) return;
    List<Reservation> blocked = new ArrayList<>();
    for (Reservation r : store.active(e.id())) {
      if (r.status() != Status.WAITLISTED) continue;
      if (e.trainingRequired() && !store.trained(r.userId(), e.id())) {
        change(r, Status.REJECTED, null, null, "培训资格失效", now);
        continue;
      }
      if (blocked.stream().noneMatch(b -> overlap(b, r.startAt(), r.endAt()))
          && store.slotsFree(e.id(), r.startAt(), r.endAt())) {
        store.allocate(r);
        change(
            r,
            Status.OFFERED,
            min(now.plusMinutes(offerMinutes), r.startAt()),
            null,
            "候补获得限时确认资格",
            now);
      } else blocked.add(r);
    }
  }

  private Reservation lock(long id) {
    Reservation found = store.reservation(id);
    store.equipment(found.equipmentId(), true);
    return store.reservation(id);
  }

  private Reservation lockOwned(Actor actor, long id) {
    Reservation r = lock(id);
    if (!actor.admin() && r.userId() != actor.id()) throw new Problem(403, "不能操作其他用户的预约");
    return r;
  }

  private Reservation change(
      Reservation r,
      Status next,
      LocalDateTime deadline,
      Long actor,
      String reason,
      LocalDateTime now) {
    if (store
            .jdbc()
            .update(
                "UPDATE reservation SET status=?,deadline=?,updated_at=? WHERE id=? AND status=?",
                next.name(),
                deadline,
                now,
                r.id(),
                r.status().name())
        != 1) throw Problem.conflict("预约状态已改变，请刷新");
    if (!next.holdsSlots()) store.release(r.id());
    Reservation changed = store.reservation(r.id());
    store.audit(changed, r.status(), actor, reason, now);
    return changed;
  }

  private void require(Reservation r, Status status) {
    if (r.status() != status) throw Problem.conflict("当前状态为 " + r.status() + "，不能执行此操作");
  }

  private boolean overlap(Reservation r, LocalDateTime start, LocalDateTime end) {
    return r.startAt().isBefore(end) && r.endAt().isAfter(start);
  }

  private LocalDateTime min(LocalDateTime a, LocalDateTime b) {
    return a.isBefore(b) ? a : b;
  }

  private void validateTimes(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
    if (start == null
        || end == null
        || !start.isAfter(now)
        || !end.isAfter(start)
        || end.isAfter(now.plusDays(horizonDays))
        || Duration.between(start, end).toMinutes() > 480)
      throw new Problem(400, "预约须在未来 " + horizonDays + " 天内，时长为 0.5～8 小时");
    if (start.getMinute() % 30 != 0
        || end.getMinute() % 30 != 0
        || start.getSecond() != 0
        || end.getSecond() != 0
        || start.getNano() != 0
        || end.getNano() != 0) throw new Problem(400, "预约起止时间须对齐整点或半点");
  }
}
