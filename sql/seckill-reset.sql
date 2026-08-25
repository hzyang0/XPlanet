-- Development-only reset/seed script. Run it manually against the local xplanet database;
-- it deliberately does not drop Docker volumes.
USE xplanet;
DELETE FROM seckill_order_outbox;
DELETE FROM seckill_order;
DELETE FROM seckill_request;
UPDATE seckill_activity SET available_stock = total_stock, status = 1,
  start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR), end_time = DATE_ADD(NOW(), INTERVAL 7 DAY)
WHERE id = 1;
