-- Make each persisted evidence fragment independently verifiable after source extraction.
USE xplanet;

ALTER TABLE `evidence_chunk`
    ADD COLUMN `content_hash` CHAR(64) NULL AFTER `content`;

UPDATE `evidence_chunk`
SET `content_hash` = LOWER(SHA2(`content`, 256))
WHERE `content_hash` IS NULL;

ALTER TABLE `evidence_chunk`
    MODIFY COLUMN `content_hash` CHAR(64) NOT NULL;
