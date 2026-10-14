UPDATE transfers SET expires_at = LEAST(expires_at, created_at + INTERVAL '2 days');
ALTER TABLE transfers ADD CONSTRAINT maximum_retention CHECK (expires_at <= created_at + INTERVAL '2 days');
