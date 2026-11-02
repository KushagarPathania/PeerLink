-- Baseline migration: establishes Flyway version history for the PeerLink schema.
-- Tables for users, peers, file metadata, permissions and transfers are added in later
-- versioned migrations (V2__..., V3__...). File contents are never stored in the database.
SELECT 1;
