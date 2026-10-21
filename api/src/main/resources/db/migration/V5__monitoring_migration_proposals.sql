CREATE TABLE IF NOT EXISTS ops_monitoring_migration_proposal (
  id bigserial PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  asset_id bigint NOT NULL,
  project_id bigint NOT NULL,
  observed_snapshot text NOT NULL,
  interval_seconds integer NOT NULL CHECK (interval_seconds BETWEEN 10 AND 86400),
  plan_name varchar(255) NOT NULL,
  status varchar(255) NOT NULL,
  created_at timestamp NOT NULL,
  confirmed_at timestamp,
  prepared_plan_id bigint,
  result_plan_id bigint
);

CREATE INDEX IF NOT EXISTS idx_monitoring_migration_proposal_asset
  ON ops_monitoring_migration_proposal(asset_id, status);
