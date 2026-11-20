-- Rerunnable migration for existing installations: add provider routing column.
SET @provider_code_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='model_request' AND column_name='provider_code')=0,
  'ALTER TABLE model_request ADD COLUMN provider_code VARCHAR(64) NULL', 'SELECT 1');
PREPARE provider_code_stmt FROM @provider_code_ddl;
EXECUTE provider_code_stmt;
DEALLOCATE PREPARE provider_code_stmt;
