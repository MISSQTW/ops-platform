ALTER TABLE IF EXISTS ops_inspection_plan ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0;

DELETE FROM ops_monitoring_config_task newer
USING ops_monitoring_config_task older
WHERE newer.id > older.id
  AND newer.plan_id = older.plan_id
  AND newer.asset_id = older.asset_id
  AND newer.desired_version = older.desired_version
  AND newer.operation = older.operation;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ops_monitoring_task_target
  ON ops_monitoring_config_task(plan_id, asset_id, desired_version, operation);
