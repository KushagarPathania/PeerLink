-- V5: direct file transfer between peer agents over mutually authenticated TLS.
--
-- * peers.tls_certificate_sha256: fingerprint of the certificate the peer's listener presents.
--   Agents generate an ephemeral key pair per process and report the fingerprint with every
--   heartbeat; no private key is stored anywhere.
-- * connection_tickets.initiator_certificate_sha256: fingerprint of the certificate the connecting
--   agent will present, bound to the ticket so the target can verify it.
-- * connection_tickets.transfer_id: a ticket may authorize exactly one file transfer.
-- * file_transfers gains the REJECTED status and more failure codes; transfer_events records
--   which peer reported each change.
-- File contents are never stored in the database.

ALTER TABLE peers
    ADD COLUMN tls_certificate_sha256 VARCHAR(64),
    ADD CONSTRAINT ck_peers_tls_certificate_sha256 CHECK (
        tls_certificate_sha256 IS NULL OR tls_certificate_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE connection_tickets
    ADD COLUMN initiator_certificate_sha256 VARCHAR(64),
    ADD COLUMN transfer_id UUID,
    ADD CONSTRAINT fk_connection_tickets_transfer FOREIGN KEY (transfer_id)
        REFERENCES file_transfers (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_connection_tickets_initiator_certificate CHECK (
        initiator_certificate_sha256 IS NULL OR initiator_certificate_sha256 ~ '^[0-9a-f]{64}$');

CREATE INDEX ix_connection_tickets_transfer ON connection_tickets (transfer_id);

ALTER TABLE file_transfers
    DROP CONSTRAINT ck_file_transfers_status,
    ADD CONSTRAINT ck_file_transfers_status CHECK (status IN (
        'REQUESTED', 'AUTHORIZED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'FAILED', 'CANCELLED')),
    DROP CONSTRAINT ck_file_transfers_error_code,
    ADD CONSTRAINT ck_file_transfers_error_code CHECK (error_code IS NULL OR error_code IN (
        'PERMISSION_DENIED', 'TOKEN_REJECTED', 'PEER_UNREACHABLE', 'CONNECTION_LOST',
        'FILE_UNAVAILABLE', 'CHECKSUM_MISMATCH', 'STORAGE_ERROR', 'TIMEOUT',
        'PEER_BUSY', 'FILE_TOO_LARGE', 'INSUFFICIENT_STORAGE', 'FILE_CHANGED', 'METADATA_MISMATCH',
        'PROTOCOL_ERROR', 'AGENT_SHUTDOWN', 'REQUEST_EXPIRED', 'SERVER_UNAVAILABLE')),
    -- Every unsuccessful end (failed or rejected) must say why.
    DROP CONSTRAINT ck_file_transfers_failed_has_error,
    ADD CONSTRAINT ck_file_transfers_failed_has_error CHECK (
        status NOT IN ('FAILED', 'REJECTED') OR error_code IS NOT NULL);

-- Finding the next transfer a receiving peer should fetch.
CREATE INDEX ix_file_transfers_receiver_status ON file_transfers (receiver_peer_id, status, created_at);

ALTER TABLE transfer_events
    DROP CONSTRAINT ck_transfer_events_from_status,
    ADD CONSTRAINT ck_transfer_events_from_status CHECK (from_status IS NULL OR from_status IN (
        'REQUESTED', 'AUTHORIZED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'FAILED', 'CANCELLED')),
    DROP CONSTRAINT ck_transfer_events_to_status,
    ADD CONSTRAINT ck_transfer_events_to_status CHECK (to_status IN (
        'REQUESTED', 'AUTHORIZED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'FAILED', 'CANCELLED')),
    ADD COLUMN reported_by_peer_id UUID,
    ADD CONSTRAINT fk_transfer_events_reported_by FOREIGN KEY (reported_by_peer_id)
        REFERENCES peers (id) ON DELETE RESTRICT;
