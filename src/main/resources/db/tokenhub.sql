CREATE DATABASE IF NOT EXISTS tokenhub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tokenhub;

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS token_package (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL, model_code VARCHAR(64) NOT NULL, token_amount BIGINT NOT NULL,
  price DECIMAL(10,2) NOT NULL, description VARCHAR(500) NOT NULL, suitable_scenario VARCHAR(500) NULL,
  stock BIGINT NOT NULL, status TINYINT NOT NULL DEFAULT 1,
  catalog_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_package_name (name), KEY idx_package_model_status (model_code, status)
) ENGINE=InnoDB;

SET @has_catalog_version = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'token_package' AND column_name = 'catalog_version'
);
SET @catalog_version_ddl = IF(
  @has_catalog_version = 0,
  'ALTER TABLE token_package ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 1',
  'SELECT 1'
);
PREPARE tokenhub_catalog_version_stmt FROM @catalog_version_ddl;
EXECUTE tokenhub_catalog_version_stmt;
DEALLOCATE PREPARE tokenhub_catalog_version_stmt;
UPDATE token_package
SET catalog_version = GREATEST(catalog_version, CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6)) * 1000000 AS UNSIGNED));

CREATE TABLE IF NOT EXISTS seckill_activity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, package_id BIGINT NOT NULL, seckill_price DECIMAL(10,2) NOT NULL,
  stock BIGINT NOT NULL, start_time DATETIME NOT NULL, end_time DATETIME NOT NULL, status TINYINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_activity_package (package_id), KEY idx_activity_time_status (start_time, end_time, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(32) NOT NULL, user_id BIGINT NOT NULL, package_id BIGINT NOT NULL,
  order_type ENUM('NORMAL','SECKILL') NOT NULL, seckill_activity_id BIGINT NULL, request_id VARCHAR(64) NULL, purchase_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  original_price DECIMAL(10,2) NOT NULL, pay_price DECIMAL(10,2) NOT NULL, token_amount BIGINT NOT NULL,
  status INT NOT NULL CHECK (status IN (101,102,103,201,301,302,401)), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, paid_at DATETIME NULL, completed_at DATETIME NULL, expired_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_user_purchase_key (user_id, purchase_key), UNIQUE KEY uk_order_no (order_no), UNIQUE KEY uk_request_id (request_id), UNIQUE KEY uk_user_activity (user_id, seckill_activity_id),
  KEY idx_order_user_id (user_id), KEY idx_order_status (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS token_wallet (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, model_code VARCHAR(64) NOT NULL,
  available_token BIGINT NOT NULL DEFAULT 0, frozen_token BIGINT NOT NULL DEFAULT 0, credit_limit BIGINT NOT NULL DEFAULT 5000, version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wallet_user_model (user_id, model_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS token_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, model_code VARCHAR(64) NOT NULL, biz_id VARCHAR(64) NOT NULL,
  type ENUM('PURCHASE','SECKILL_PURCHASE','CONSUME','ADJUSTMENT') NOT NULL, amount BIGINT NOT NULL, balance_before BIGINT NOT NULL, balance_after BIGINT NOT NULL,
  remark VARCHAR(255) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_ledger_biz_id (biz_id), KEY idx_ledger_user_model (user_id, model_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS api_key (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL, key_prefix VARCHAR(16) NOT NULL, key_hash CHAR(64) NOT NULL,
    allowed_models TEXT NOT NULL, status TINYINT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_used_at DATETIME NULL,
  UNIQUE KEY uk_api_key_hash (key_hash), KEY idx_api_key_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS model_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, request_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, api_key_id BIGINT NOT NULL, model_code VARCHAR(64) NOT NULL,
  input_tokens BIGINT NULL, output_tokens BIGINT NULL, total_tokens BIGINT NULL, reserved_tokens BIGINT NOT NULL,
  status ENUM('PROCESSING','SUCCESS','FAILED','BILLING_UNKNOWN') NOT NULL, retry_count INT NOT NULL DEFAULT 0,
  provider_cost_status ENUM('KNOWN','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN', result_reference TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at DATETIME NULL,
  UNIQUE KEY uk_model_request_id (request_id), KEY idx_model_request_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, message_id VARCHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL, biz_id VARCHAR(64) NOT NULL,
  topic VARCHAR(64) NOT NULL, payload TEXT NOT NULL, status TINYINT NOT NULL DEFAULT 0, retry_count INT NOT NULL DEFAULT 0, next_retry_time DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_message_id (message_id), UNIQUE KEY uk_message_event_biz (event_type, biz_id), KEY idx_message_status_retry (status, next_retry_time), KEY idx_message_publish (status, next_retry_time, id)
) ENGINE=InnoDB;

INSERT INTO `user` (username, password_hash, status) VALUES ('demo', '$2a$10$uBzFCXGB94jqLLwE.TcRkeX8gPijz1.UMHbv7EEJDWiqTcFo3yBt6', 1)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = VALUES(status);
INSERT INTO token_package (name, model_code, token_amount, price, description, suitable_scenario, stock, status) VALUES
('GPT-4o Mini 开发包', 'gpt-4o-mini', 1000000, 19.90, '适合日常开发与原型验证。', '代码补全,聊天机器人', 1000, 1),
('GPT-4o 专业包', 'gpt-4o', 500000, 49.90, '适合高质量推理与生产集成。', '复杂问答,内容生成', 500, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO seckill_activity (package_id, seckill_price, stock, start_time, end_time, status)
SELECT id, 9.90, 100, DATE_SUB(NOW(), INTERVAL 5 MINUTE), DATE_ADD(NOW(), INTERVAL 1 DAY), 1
FROM token_package
WHERE NOT EXISTS (SELECT 1 FROM seckill_activity)
ORDER BY id
LIMIT 1;

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
