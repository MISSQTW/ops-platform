CREATE TABLE IF NOT EXISTS ops_alert_handling_event (
  id bigserial PRIMARY KEY,
  alert_id bigint NOT NULL,
  action varchar(255) NOT NULL,
  from_status varchar(255),
  to_status varchar(255),
  operator_name varchar(255),
  note varchar(1000),
  occurred_at timestamp NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_handling_event_alert
  ON ops_alert_handling_event(alert_id, occurred_at, id);

ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS external_lifecycle varchar(255);
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS handling_status varchar(255);
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS first_occurred_at timestamp;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS last_occurred_at timestamp;
ALTER TABLE IF EXISTS ops_alert ADD COLUMN IF NOT EXISTS resolved_at timestamp;

UPDATE ops_alert SET version = 0 WHERE version IS NULL;
ALTER TABLE IF EXISTS ops_alert ALTER COLUMN version SET NOT NULL;
UPDATE ops_alert
SET external_lifecycle = CASE WHEN status = 'RESOLVED' THEN 'RESOLVED' ELSE 'FIRING' END
WHERE external_lifecycle IS NULL;
UPDATE ops_alert
SET handling_status = CASE
  WHEN status IN ('OPEN', 'ACKNOWLEDGED', 'CLOSED') THEN status
  ELSE 'OPEN'
END
WHERE handling_status IS NULL;
UPDATE ops_alert SET first_occurred_at = occurred_at WHERE first_occurred_at IS NULL;
UPDATE ops_alert SET last_occurred_at = occurred_at WHERE last_occurred_at IS NULL;
UPDATE ops_alert SET resolved_at = occurred_at
WHERE resolved_at IS NULL AND external_lifecycle = 'RESOLVED';
