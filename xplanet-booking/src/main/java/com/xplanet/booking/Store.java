package com.xplanet.booking;

import static com.xplanet.booking.Models.*;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;

@Repository
public class Store {
  private final JdbcTemplate jdbc;

  public Store(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  private final RowMapper<Equipment> equipmentMapper =
      (r, n) ->
          new Equipment(
              r.getLong("id"),
              r.getString("name"),
              r.getString("category"),
              r.getString("location"),
              r.getString("description"),
              r.getBoolean("enabled"),
              r.getBoolean("approval_required"),
              r.getBoolean("training_required"),
              r.getLong("version"));
  private final RowMapper<Reservation> reservationMapper =
      (r, n) ->
          new Reservation(
              r.getLong("id"),
              r.getLong("equipment_id"),
              r.getLong("user_id"),
              r.getString("request_key"),
              time(r, "start_at"),
              time(r, "end_at"),
              r.getString("purpose"),
              Status.valueOf(r.getString("status")),
              time(r, "deadline"),
              time(r, "created_at"),
              time(r, "updated_at"));

  static LocalDateTime time(ResultSet r, String column) throws SQLException {
    // DATETIME is a wall-clock value in our explicit UTC convention. Converting through
    // Timestamp would apply the JVM timezone and shift values on non-UTC hosts.
    return r.getObject(column, LocalDateTime.class);
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (r, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          ResultSetMetaData meta = r.getMetaData();
          for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(
                meta.getColumnLabel(i),
                meta.getColumnType(i) == Types.TIMESTAMP
                    ? r.getObject(i, LocalDateTime.class)
                    : r.getObject(i));
          return row;
        },
        args);
  }

  long insert(String sql, Object... args) {
    KeyHolder holder = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
          return p;
        },
        holder);
    return Objects.requireNonNull(holder.getKey()).longValue();
  }

  Equipment equipment(long id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM equipment WHERE id=?" + (lock ? " FOR UPDATE" : ""), equipmentMapper, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new Problem(404, "设备不存在"));
  }

  void lockUser(long userId) {
    if (jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class, userId)
        .isEmpty()) throw new Problem(404, "用户不存在");
  }

  List<Equipment> equipmentList(String query, int limit, int offset) {
    String like = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    return jdbc.query(
        "SELECT * FROM equipment WHERE name LIKE ? ESCAPE '!' OR category LIKE ? ESCAPE '!' OR"
            + " location LIKE ? ESCAPE '!' ORDER BY id LIMIT ? OFFSET ?",
        equipmentMapper,
        like,
        like,
        like,
        limit,
        offset);
  }

  List<Long> equipmentIds() {
    return jdbc.queryForList("SELECT id FROM equipment ORDER BY id", Long.class);
  }

  Reservation reservation(long id) {
    return jdbc.query("SELECT * FROM reservation WHERE id=?", reservationMapper, id).stream()
        .findFirst()
        .orElseThrow(() -> new Problem(404, "预约不存在"));
  }

  Optional<Reservation> byKey(long userId, String key) {
    return jdbc
        .query(
            "SELECT * FROM reservation WHERE user_id=? AND request_key=?",
            reservationMapper,
            userId,
            key)
        .stream()
        .findFirst();
  }

  List<Reservation> active(long equipmentId) {
    return jdbc.query(
        "SELECT * FROM reservation WHERE equipment_id=? AND status IN"
            + " ('WAITLISTED','OFFERED','PENDING_APPROVAL','CONFIRMED','IN_USE') ORDER BY id",
        reservationMapper,
        equipmentId);
  }

  List<Reservation> listReservations(Actor actor, int limit, int offset) {
    if (actor.admin())
      return jdbc.query(
          "SELECT * FROM reservation ORDER BY id DESC LIMIT ? OFFSET ?",
          reservationMapper,
          limit,
          offset);
    return jdbc.query(
        "SELECT * FROM reservation WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
        reservationMapper,
        actor.id(),
        limit,
        offset);
  }

  boolean trained(long userId, long equipmentId) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM qualification WHERE user_id=? AND equipment_id=?",
            Integer.class,
            userId,
            equipmentId)
        > 0;
  }

  boolean slotsFree(long equipmentId, LocalDateTime start, LocalDateTime end) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation_slot WHERE equipment_id=? AND slot_at>=? AND"
                + " slot_at<?",
            Integer.class,
            equipmentId,
            start,
            end)
        == 0;
  }

  void allocate(Reservation r) {
    for (LocalDateTime t = r.startAt(); t.isBefore(r.endAt()); t = t.plusMinutes(30))
      jdbc.update(
          "INSERT INTO reservation_slot(equipment_id,slot_at,reservation_id) VALUES(?,?,?)",
          r.equipmentId(),
          t,
          r.id());
  }

  void release(long id) {
    jdbc.update("DELETE FROM reservation_slot WHERE reservation_id=?", id);
  }

  void audit(Reservation r, Status from, Long actor, String reason, LocalDateTime now) {
    jdbc.update(
        "INSERT INTO"
            + " reservation_audit(reservation_id,from_status,to_status,actor_id,reason,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        r.id(),
        from == null ? null : from.name(),
        r.status().name(),
        actor,
        reason,
        now);
    jdbc.update(
        "INSERT INTO event_outbox(reservation_id,user_id,message,created_at) VALUES(?,?,?,?)",
        r.id(),
        r.userId(),
        "预约 #" + r.id() + " → " + r.status().name() + "：" + reason,
        now);
  }
}
