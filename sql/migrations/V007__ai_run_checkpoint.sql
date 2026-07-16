-- Durable Agent checkpoints stored with the existing node execution trace.
USE xplanet;

ALTER TABLE `ai_run_step`
    ADD COLUMN `state_version` INT NOT NULL DEFAULT 1 AFTER `input_hash`,
    ADD COLUMN `checkpoint_json` MEDIUMTEXT NULL AFTER `status`;
