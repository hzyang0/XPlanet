CREATE TABLE app_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(32) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(12) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CHECK (role IN ('USER','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE auth_session (
 token_hash CHAR(64) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_session_expiry ON auth_session(expires_at);
CREATE TABLE equipment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 category VARCHAR(40) NOT NULL,
 location VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 approval_required BOOLEAN NOT NULL DEFAULT FALSE,
 training_required BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE qualification (
 user_id BIGINT NOT NULL,
 equipment_id BIGINT NOT NULL,
 granted_at DATETIME(6) NOT NULL,
 PRIMARY KEY(user_id,equipment_id),
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(equipment_id) REFERENCES equipment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE reservation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 equipment_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 start_at DATETIME(6) NOT NULL,
 end_at DATETIME(6) NOT NULL,
 purpose VARCHAR(300) NOT NULL,
 status VARCHAR(24) NOT NULL,
 deadline DATETIME(6),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE(user_id,request_key),
 CHECK (start_at < end_at),
 CHECK (status IN ('WAITLISTED','OFFERED','PENDING_APPROVAL','CONFIRMED','IN_USE','COMPLETED','CANCELLED','REJECTED','EXPIRED','NO_SHOW')),
 FOREIGN KEY(equipment_id) REFERENCES equipment(id),
 FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_reservation_resource ON reservation(equipment_id,status,start_at,end_at);
CREATE INDEX idx_reservation_user ON reservation(user_id,id);
CREATE INDEX idx_reservation_deadline ON reservation(status,deadline);
CREATE TABLE reservation_slot (
 equipment_id BIGINT NOT NULL,
 slot_at DATETIME(6) NOT NULL,
 reservation_id BIGINT NOT NULL,
 PRIMARY KEY(equipment_id,slot_at),
 FOREIGN KEY(equipment_id) REFERENCES equipment(id),
 FOREIGN KEY(reservation_id) REFERENCES reservation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_slot_reservation ON reservation_slot(reservation_id);
CREATE TABLE reservation_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 reservation_id BIGINT NOT NULL,
 from_status VARCHAR(24),
 to_status VARCHAR(24) NOT NULL,
 actor_id BIGINT,
 reason VARCHAR(300) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(reservation_id) REFERENCES reservation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE event_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 reservation_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 message VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 delivered_at DATETIME(6),
 FOREIGN KEY(reservation_id) REFERENCES reservation(id),
 FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_outbox_pending ON event_outbox(delivered_at,id);
CREATE TABLE inbox_notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_id BIGINT NOT NULL UNIQUE,
 user_id BIGINT NOT NULL,
 reservation_id BIGINT NOT NULL,
 message VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 read_at DATETIME(6),
 FOREIGN KEY(event_id) REFERENCES event_outbox(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_inbox_user ON inbox_notification(user_id,id);
