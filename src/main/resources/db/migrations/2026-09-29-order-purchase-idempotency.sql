-- Rerunnable migration for existing installations.
SET @purchase_key_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='orders' AND column_name='purchase_key')=0,
  'ALTER TABLE orders ADD COLUMN purchase_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE purchase_key_stmt FROM @purchase_key_ddl;
EXECUTE purchase_key_stmt;
DEALLOCATE PREPARE purchase_key_stmt;
SET @purchase_index_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name='uk_order_user_purchase_key')=0,
  'ALTER TABLE orders ADD UNIQUE KEY uk_order_user_purchase_key (user_id,purchase_key)', 'SELECT 1');
PREPARE purchase_index_stmt FROM @purchase_index_ddl;
EXECUTE purchase_index_stmt;
DEALLOCATE PREPARE purchase_index_stmt;
