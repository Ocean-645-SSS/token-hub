CREATE TABLE IF NOT EXISTS seckill_activity (
 id BIGINT PRIMARY KEY,package_id BIGINT,seckill_price DECIMAL(10,2),stock BIGINT,
 start_time TIMESTAMP,end_time TIMESTAMP,status INT
);
CREATE TABLE IF NOT EXISTS token_package (
 id BIGINT PRIMARY KEY,name VARCHAR(128),model_code VARCHAR(64),token_amount BIGINT,
 price DECIMAL(10,2),description VARCHAR(500),suitable_scenario VARCHAR(500),stock BIGINT,status INT,catalog_version BIGINT
);
CREATE TABLE IF NOT EXISTS seckill_reservation (
 request_id VARCHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL,activity_id BIGINT NOT NULL,
 package_id BIGINT NOT NULL,state VARCHAR(32) NOT NULL,order_no VARCHAR(32),
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,activity_id)
);
CREATE TABLE IF NOT EXISTS seckill_inventory (
 activity_id BIGINT PRIMARY KEY,available_stock BIGINT NOT NULL CHECK(available_stock>=0),version BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(32) UNIQUE,user_id BIGINT,package_id BIGINT,
 order_type VARCHAR(16),seckill_activity_id BIGINT,request_id VARCHAR(64) UNIQUE,purchase_key VARCHAR(64),
 original_price DECIMAL(10,2),pay_price DECIMAL(10,2),token_amount BIGINT,status INT,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,paid_at TIMESTAMP,completed_at TIMESTAMP,expired_at TIMESTAMP,
 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,seckill_activity_id)
);
CREATE TABLE IF NOT EXISTS local_message (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,message_id VARCHAR(64) UNIQUE,event_type VARCHAR(64),biz_id VARCHAR(64),
 topic VARCHAR(64),payload VARCHAR(4000),status INT DEFAULT 0,retry_count INT DEFAULT 0,
 next_retry_time TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(event_type,biz_id)
);
