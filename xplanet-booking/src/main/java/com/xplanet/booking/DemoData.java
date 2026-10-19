package com.xplanet.booking;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoData implements ApplicationRunner {
  private final Store store;
  private final AuthService auth;
  private final Clock clock;
  private final boolean demo;
  private final String adminPassword;

  public DemoData(
      Store store,
      AuthService auth,
      Clock clock,
      @Value("${booking.seed-demo:false}") boolean demo,
      @Value("${booking.admin-password:}") String adminPassword) {
    this.store = store;
    this.auth = auth;
    this.clock = clock;
    this.demo = demo;
    this.adminPassword = adminPassword;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!adminPassword.isBlank()
        && store
                .jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE username='admin'", Integer.class)
            == 0) {
      if (adminPassword.length() < 8) throw new IllegalArgumentException("ADMIN_PASSWORD 至少需要 8 位");
      store.insert(
          "INSERT INTO app_user(username,password_hash,role,created_at)"
              + " VALUES('admin',?,'ADMIN',?)",
          auth.encode(adminPassword),
          LocalDateTime.now(clock));
    }
    if (!demo) return;
    if (store
            .jdbc()
            .queryForObject("SELECT COUNT(*) FROM app_user WHERE username='alice'", Integer.class)
        == 0) auth.register(new com.xplanet.booking.Models.Register("alice", "password123"));
    if (store.jdbc().queryForObject("SELECT COUNT(*) FROM equipment", Integer.class) > 0) return;
    store
        .jdbc()
        .update(
            "INSERT INTO"
                + " equipment(name,category,location,description,approval_required,training_required)"
                + " VALUES(?,?,?,?,?,?)",
            "示波器 · A01",
            "电子测量",
            "实验楼 302",
            "通用电子测量设备；直接预约，半小时一个时段。",
            false,
            false);
    store
        .jdbc()
        .update(
            "INSERT INTO"
                + " equipment(name,category,location,description,approval_required,training_required)"
                + " VALUES(?,?,?,?,?,?)",
            "GPU 工作站 · G01",
            "计算资源",
            "计算中心 201",
            "短时交互实验工作站；需要管理员审批。",
            true,
            false);
    store
        .jdbc()
        .update(
            "INSERT INTO"
                + " equipment(name,category,location,description,approval_required,training_required)"
                + " VALUES(?,?,?,?,?,?)",
            "共聚焦显微镜 · M01",
            "成像设备",
            "实验楼 506",
            "须获得设备培训资格，并经管理员审批后使用。",
            true,
            true);
  }
}
