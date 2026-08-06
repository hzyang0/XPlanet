-- Persist the selected Agent provider per task so offline and online jobs can coexist safely.
USE xplanet;

ALTER TABLE `ai_task`
    ADD COLUMN `provider` VARCHAR(32) NOT NULL DEFAULT 'offline-demo' AFTER `question`;
