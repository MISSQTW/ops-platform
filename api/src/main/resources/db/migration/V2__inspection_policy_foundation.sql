DO $$
BEGIN
  IF to_regclass('public.ops_project') IS NOT NULL THEN
    IF EXISTS (SELECT name FROM ops_project GROUP BY name HAVING COUNT(*) > 1) THEN
      RAISE EXCEPTION '存在重复项目名称，请先清理后再升级';
    END IF;
  END IF;
END $$;

ALTER TABLE IF EXISTS ops_project ADD COLUMN IF NOT EXISTS project_type varchar(255);
ALTER TABLE IF EXISTS ops_project ADD COLUMN IF NOT EXISTS contact_phone varchar(255);
ALTER TABLE IF EXISTS ops_project ADD COLUMN IF NOT EXISTS maintenance_expires_at date;

DO $$
BEGIN
  IF to_regclass('public.ops_project') IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_ops_project_name') THEN
    ALTER TABLE ops_project ADD CONSTRAINT uk_ops_project_name UNIQUE (name);
  END IF;
END $$;

ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS project_id bigint;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS interval_seconds integer;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS failure_threshold integer;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS alert_level varchar(255);
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS lifecycle_status varchar(255) DEFAULT 'ACTIVE';
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS sync_status varchar(255) DEFAULT 'NOT_SYNCED';
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS desired_version bigint DEFAULT 1;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS last_sync_attempt_at timestamp;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS last_sync_succeeded_at timestamp;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS last_verified_at timestamp;
ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS sync_error text;

CREATE TABLE IF NOT EXISTS ops_inspection_plan_binding (
  id bigserial PRIMARY KEY,
  version bigint DEFAULT 0,
  plan_id bigint NOT NULL,
  asset_id bigint NOT NULL,
  desired_version bigint NOT NULL,
  active_owner_key bigint,
  ownership_status varchar(255) NOT NULL,
  monitor_desired_state varchar(255),
  monitor_actual_state varchar(255),
  rule_desired_state varchar(255),
  rule_actual_state varchar(255),
  hertzbeat_monitor_id bigint,
  hertzbeat_alert_rule_id bigint,
  last_sync_attempt_at timestamp,
  last_sync_succeeded_at timestamp,
  last_verified_at timestamp,
  sync_error text
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ops_plan_binding_active_owner
  ON ops_inspection_plan_binding(active_owner_key)
  WHERE active_owner_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS ops_monitoring_config_task (
  id bigserial PRIMARY KEY,
  version bigint DEFAULT 0,
  plan_id bigint NOT NULL,
  asset_id bigint,
  desired_version bigint NOT NULL,
  operation varchar(255) NOT NULL,
  status varchar(255) NOT NULL,
  attempt_count integer DEFAULT 0,
  next_attempt_at timestamp,
  created_at timestamp NOT NULL,
  started_at timestamp,
  finished_at timestamp,
  error_message varchar(255)
);

CREATE INDEX IF NOT EXISTS idx_monitoring_config_task_ready
  ON ops_monitoring_config_task(status, next_attempt_at, created_at);

ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS project_id bigint;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS inspection_plan_id bigint;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS asset_id bigint;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS inspection_plan_name varchar(255);
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS project_name_snapshot varchar(255);
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS target_name_snapshot varchar(255);
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS handled_by varchar(255);
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS handled_at timestamp;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS handling_note varchar(255);
