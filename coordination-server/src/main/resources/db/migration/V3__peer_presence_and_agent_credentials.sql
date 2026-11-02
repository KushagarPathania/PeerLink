-- V3: peer presence and peer-agent credentials.
--
-- 1. host_address becomes nullable. A peer is registered from the browser, so the server does not
--    know the device's address until the peer agent itself sends a heartbeat; the address is then
--    taken from that connection. An ONLINE peer must always have one.
-- 2. Each peer can hold one agent credential. Only the SHA-256 hash of the random credential is
--    stored; revoking a credential clears both columns.

ALTER TABLE peers ALTER COLUMN host_address DROP NOT NULL;

ALTER TABLE peers
    ADD COLUMN credential_hash      VARCHAR(64),
    ADD COLUMN credential_issued_at TIMESTAMPTZ;

ALTER TABLE peers
    ADD CONSTRAINT uk_peers_credential_hash UNIQUE (credential_hash),
    ADD CONSTRAINT ck_peers_credential_hash_format CHECK (
        credential_hash IS NULL OR credential_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_peers_credential_issued CHECK (
        (credential_hash IS NULL) = (credential_issued_at IS NULL)),
    ADD CONSTRAINT ck_peers_online_has_address CHECK (status = 'OFFLINE' OR host_address IS NOT NULL);
