CREATE TABLE transfers (
  id UUID PRIMARY KEY,
  read_hash VARCHAR(64) NOT NULL UNIQUE,
  manage_hash VARCHAR(64) NOT NULL UNIQUE,
  kind VARCHAR(8) NOT NULL,
  name VARCHAR(240) NOT NULL,
  media_type VARCHAR(180) NOT NULL,
  byte_size BIGINT NOT NULL,
  password_hash VARCHAR(256),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  max_downloads INTEGER NOT NULL CHECK(max_downloads BETWEEN 1 AND 10),
  downloads INTEGER NOT NULL DEFAULT 0 CHECK(downloads >= 0),
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  active_lease UUID,
  purged_at TIMESTAMP WITH TIME ZONE,
  CHECK(downloads <= max_downloads)
);
CREATE INDEX transfers_expiration ON transfers(expires_at);
