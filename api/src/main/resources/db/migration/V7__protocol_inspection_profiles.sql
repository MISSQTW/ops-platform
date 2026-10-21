CREATE TABLE IF NOT EXISTS ops_inspection_profile (
  asset_id bigint PRIMARY KEY,
  version bigint DEFAULT 0,
  enabled boolean NOT NULL DEFAULT false,
  revision bigint NOT NULL DEFAULT 0,
  service_type varchar(255),
  encrypted_settings text,
  checks_json text,
  checked_at timestamp,
  checked_endpoint varchar(255)
);
