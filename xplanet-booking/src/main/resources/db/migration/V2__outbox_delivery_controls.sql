ALTER TABLE event_outbox ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE event_outbox ADD COLUMN next_attempt_at DATETIME(6);
ALTER TABLE event_outbox ADD COLUMN last_error VARCHAR(300);
ALTER TABLE event_outbox ADD COLUMN failed_at DATETIME(6);
CREATE INDEX idx_outbox_ready ON event_outbox(delivered_at,failed_at,next_attempt_at,id);
CREATE INDEX idx_reservation_due_start ON reservation(status,start_at,equipment_id);
CREATE INDEX idx_reservation_due_end ON reservation(status,end_at,equipment_id);
CREATE TABLE event_retry_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(event_id) REFERENCES event_outbox(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
