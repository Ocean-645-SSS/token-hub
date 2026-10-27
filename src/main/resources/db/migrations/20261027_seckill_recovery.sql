-- Run once with API admission and OLD consumers stopped. Rerunnable without resetting balances.
CREATE TABLE IF NOT EXISTS seckill_reservation (
  request_id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,
  package_id BIGINT NOT NULL, state VARCHAR(32) NOT NULL, order_no VARCHAR(32) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_reservation_user_activity (user_id,activity_id),
  KEY idx_reservation_recovery (state,updated_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS seckill_inventory (
  activity_id BIGINT PRIMARY KEY, available_stock BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 1, CHECK (available_stock >= 0)
) ENGINE=InnoDB;

-- Old cancelled orders still awaiting stock release remain reserved until the new consumer handles them.
INSERT IGNORE INTO seckill_reservation(request_id,user_id,activity_id,package_id,state,order_no)
SELECT o.request_id,o.user_id,o.seckill_activity_id,o.package_id,
  CASE WHEN o.status IN (102,103) AND EXISTS (
    SELECT 1 FROM local_message m WHERE m.event_type='SECKILL_STOCK_RELEASE'
      AND m.biz_id=o.order_no AND m.status=1
  ) THEN 'RELEASED' ELSE 'ORDER_CREATED' END,o.order_no
FROM orders o WHERE o.order_type='SECKILL' AND o.request_id IS NOT NULL;

-- Durable messages whose order has not been created must also occupy inventory.
INSERT IGNORE INTO seckill_reservation(request_id,user_id,activity_id,package_id,state)
SELECT m.biz_id,
  CAST(JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.userId')) AS UNSIGNED),
  CAST(JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.activityId')) AS UNSIGNED),
  CAST(JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.packageId')) AS UNSIGNED),'RESERVED'
FROM local_message m WHERE m.event_type='SECKILL_ORDER' AND JSON_VALID(m.payload);

-- seckill_activity.stock remains the configured TOTAL, not the available balance.
INSERT INTO seckill_inventory(activity_id,available_stock,version)
SELECT a.id,a.stock-(SELECT COUNT(*) FROM seckill_reservation r
    WHERE r.activity_id=a.id AND r.state <> 'RELEASED'),1
FROM seckill_activity a WHERE NOT EXISTS (SELECT 1 FROM seckill_inventory i WHERE i.activity_id=a.id);
-- Negative balances deliberately fail CHECK: investigate historical overselling rather than masking it.
