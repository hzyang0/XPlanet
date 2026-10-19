CREATE TABLE equipment_block (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 equipment_id BIGINT NOT NULL,
 start_at DATETIME(6) NOT NULL,
 end_at DATETIME(6) NOT NULL,
 reason VARCHAR(300) NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(created_by,request_key),
 CHECK(start_at < end_at),
 FOREIGN KEY(equipment_id) REFERENCES equipment(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE reservation_slot MODIFY COLUMN reservation_id BIGINT NULL;
ALTER TABLE reservation_slot ADD COLUMN block_id BIGINT;
ALTER TABLE reservation_slot ADD CONSTRAINT fk_slot_block FOREIGN KEY(block_id) REFERENCES equipment_block(id);
ALTER TABLE reservation_slot ADD CONSTRAINT chk_slot_owner CHECK(
 (reservation_id IS NOT NULL AND block_id IS NULL) OR
 (reservation_id IS NULL AND block_id IS NOT NULL)
);
CREATE INDEX idx_slot_block ON reservation_slot(block_id);
CREATE INDEX idx_block_resource ON equipment_block(equipment_id,active,start_at,end_at);
CREATE TABLE equipment_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 equipment_id BIGINT NOT NULL,
 actor_id BIGINT,
 action VARCHAR(40) NOT NULL,
 reason VARCHAR(300) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(equipment_id) REFERENCES equipment(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE reservation_reschedule (
 user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 source_id BIGINT NOT NULL,
 replacement_id BIGINT NOT NULL,
 start_at DATETIME(6) NOT NULL,
 end_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(user_id,request_key),
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(source_id) REFERENCES reservation(id),
 FOREIGN KEY(replacement_id) REFERENCES reservation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
