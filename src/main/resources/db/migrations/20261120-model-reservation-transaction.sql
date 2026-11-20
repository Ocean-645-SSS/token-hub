-- Pause old application writers before applying this migration.
-- Legacy PROCESSING requests must be drained or reconciled against the old Redis
-- reservations and database wallet before cutover. The default deliberately
-- does not claim a database reservation for those legacy rows.
ALTER TABLE model_request ADD COLUMN reservation_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE token_ledger MODIFY COLUMN biz_id VARCHAR(78) NOT NULL;
