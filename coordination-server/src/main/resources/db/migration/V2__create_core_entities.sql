-- V2: core PeerLink schema.
--
-- Only metadata lives here. File contents never touch the database or the server, and no
-- local filesystem paths are stored: peers keep their own mapping of file IDs to local paths.
--
-- Deletion policy: foreign keys RESTRICT by default so transfer history cannot be erased
-- indirectly. Accounts are disabled (users.enabled) and files hidden (shared_files.available)
-- instead of being deleted. Only share_permissions cascade, because a permission has no
-- meaning without its file or its recipient.
--
-- Several composite foreign keys enforce ownership rules at the database level:
--   * a file's source peer must be one of the owner's devices,
--   * only the file owner can grant a permission,
--   * a transfer's sender must be the file's source peer,
--   * a transfer's receiver must be one of the requesting user's devices.

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(32)  NOT NULL,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email),
    -- Stored lower-case so uniqueness is effectively case-insensitive.
    CONSTRAINT ck_users_username_format CHECK (username ~ '^[a-z0-9._-]{3,32}$'),
    CONSTRAINT ck_users_email_format CHECK (email = lower(email) AND email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    CONSTRAINT ck_users_password_hash_not_blank CHECK (btrim(password_hash) <> '')
);

CREATE TABLE peers (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL,
    device_name       VARCHAR(100) NOT NULL,
    -- Address observed by the server, not the one the peer claims for itself.
    host_address      INET         NOT NULL,
    listening_port    INTEGER      NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_peers_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_peers_user_device_name UNIQUE (user_id, device_name),
    -- Target for composite foreign keys that require "peer X belongs to user Y".
    CONSTRAINT uk_peers_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_peers_device_name_not_blank CHECK (btrim(device_name) <> ''),
    CONSTRAINT ck_peers_listening_port CHECK (listening_port BETWEEN 1 AND 65535),
    CONSTRAINT ck_peers_status CHECK (status IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT ck_peers_online_has_heartbeat CHECK (status = 'OFFLINE' OR last_heartbeat_at IS NOT NULL)
);

CREATE INDEX ix_peers_status_heartbeat ON peers (status, last_heartbeat_at);

CREATE TABLE shared_files (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id         UUID         NOT NULL,
    source_peer_id   UUID         NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_size_bytes  BIGINT       NOT NULL,
    sha256_checksum  VARCHAR(64)  NOT NULL,
    content_type     VARCHAR(255),
    chunk_size_bytes INTEGER      NOT NULL,
    total_chunks     INTEGER      NOT NULL,
    available        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_shared_files_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shared_files_source_peer_owner FOREIGN KEY (source_peer_id, owner_id)
        REFERENCES peers (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT uk_shared_files_id_owner UNIQUE (id, owner_id),
    CONSTRAINT uk_shared_files_id_source_peer UNIQUE (id, source_peer_id),
    -- A bare file name only: no directories, traversal segments or control characters.
    CONSTRAINT ck_shared_files_file_name CHECK (
        btrim(file_name) <> '' AND file_name NOT IN ('.', '..') AND file_name !~ '[/\\[:cntrl:]]'),
    CONSTRAINT ck_shared_files_size CHECK (file_size_bytes >= 0),
    CONSTRAINT ck_shared_files_checksum CHECK (sha256_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_shared_files_chunk_size CHECK (chunk_size_bytes BETWEEN 1 AND 16777216),
    CONSTRAINT ck_shared_files_total_chunks CHECK (
        total_chunks = (file_size_bytes + chunk_size_bytes - 1) / chunk_size_bytes)
);

CREATE INDEX ix_shared_files_owner_available ON shared_files (owner_id, available);
CREATE INDEX ix_shared_files_owner_file_name ON shared_files (owner_id, file_name);
CREATE INDEX ix_shared_files_source_peer_owner ON shared_files (source_peer_id, owner_id);

CREATE TABLE share_permissions (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    shared_file_id    UUID        NOT NULL,
    permitted_user_id UUID        NOT NULL,
    granted_by_id     UUID        NOT NULL,
    expires_at        TIMESTAMPTZ,
    revoked           BOOLEAN     NOT NULL DEFAULT FALSE,
    revoked_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_share_permissions_file FOREIGN KEY (shared_file_id)
        REFERENCES shared_files (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_permissions_granted_by_owner FOREIGN KEY (shared_file_id, granted_by_id)
        REFERENCES shared_files (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT fk_share_permissions_permitted_user FOREIGN KEY (permitted_user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_share_permissions_file_user UNIQUE (shared_file_id, permitted_user_id),
    CONSTRAINT ck_share_permissions_not_self CHECK (permitted_user_id <> granted_by_id),
    CONSTRAINT ck_share_permissions_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_share_permissions_revocation CHECK (revoked = (revoked_at IS NOT NULL))
);

CREATE INDEX ix_share_permissions_permitted_user ON share_permissions (permitted_user_id);

CREATE TABLE file_transfers (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    shared_file_id    UUID        NOT NULL,
    sender_peer_id    UUID        NOT NULL,
    receiver_peer_id  UUID        NOT NULL,
    requested_by_id   UUID        NOT NULL,
    status            VARCHAR(16) NOT NULL,
    total_bytes       BIGINT      NOT NULL,
    transferred_bytes BIGINT      NOT NULL DEFAULT 0,
    total_chunks      INTEGER     NOT NULL,
    completed_chunks  INTEGER     NOT NULL DEFAULT 0,
    -- NULL until the receiver has verified the SHA-256 checksum.
    checksum_verified BOOLEAN,
    error_code        VARCHAR(40),
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT fk_file_transfers_file_sender FOREIGN KEY (shared_file_id, sender_peer_id)
        REFERENCES shared_files (id, source_peer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_file_transfers_receiver_requester FOREIGN KEY (receiver_peer_id, requested_by_id)
        REFERENCES peers (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_file_transfers_status CHECK (
        status IN ('REQUESTED', 'AUTHORIZED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_file_transfers_error_code CHECK (error_code IS NULL OR error_code IN (
        'PERMISSION_DENIED', 'TOKEN_REJECTED', 'PEER_UNREACHABLE', 'CONNECTION_LOST',
        'FILE_UNAVAILABLE', 'CHECKSUM_MISMATCH', 'STORAGE_ERROR', 'TIMEOUT')),
    CONSTRAINT ck_file_transfers_distinct_peers CHECK (sender_peer_id <> receiver_peer_id),
    CONSTRAINT ck_file_transfers_bytes CHECK (total_bytes >= 0 AND transferred_bytes BETWEEN 0 AND total_bytes),
    CONSTRAINT ck_file_transfers_chunks CHECK (total_chunks >= 0 AND completed_chunks BETWEEN 0 AND total_chunks),
    CONSTRAINT ck_file_transfers_started CHECK (
        status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL),
    -- A transfer may only be COMPLETED once every byte arrived and the checksum was verified.
    -- "IS TRUE" (not "= TRUE") so a NULL checksum_verified fails the check instead of passing it.
    CONSTRAINT ck_file_transfers_completed_verified CHECK (
        status <> 'COMPLETED' OR (checksum_verified IS TRUE
                                  AND transferred_bytes = total_bytes
                                  AND completed_chunks = total_chunks
                                  AND completed_at IS NOT NULL)),
    CONSTRAINT ck_file_transfers_failed_has_error CHECK (status <> 'FAILED' OR error_code IS NOT NULL),
    CONSTRAINT ck_file_transfers_timeline CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);

CREATE INDEX ix_file_transfers_file_sender ON file_transfers (shared_file_id, sender_peer_id);
CREATE INDEX ix_file_transfers_sender_created ON file_transfers (sender_peer_id, created_at DESC);
CREATE INDEX ix_file_transfers_receiver_created ON file_transfers (receiver_peer_id, requested_by_id, created_at DESC);
CREATE INDEX ix_file_transfers_requested_by_created ON file_transfers (requested_by_id, created_at DESC);
CREATE INDEX ix_file_transfers_status_updated ON file_transfers (status, updated_at);

-- Append-only audit trail of every status change of a transfer.
CREATE TABLE transfer_events (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id     UUID        NOT NULL,
    sequence_number INTEGER     NOT NULL,
    from_status     VARCHAR(16),
    to_status       VARCHAR(16) NOT NULL,
    error_code      VARCHAR(40),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_transfer_events_transfer FOREIGN KEY (transfer_id)
        REFERENCES file_transfers (id) ON DELETE RESTRICT,
    CONSTRAINT uk_transfer_events_transfer_sequence UNIQUE (transfer_id, sequence_number),
    CONSTRAINT ck_transfer_events_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_transfer_events_from_status CHECK (from_status IS NULL OR from_status IN (
        'REQUESTED', 'AUTHORIZED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_transfer_events_to_status CHECK (to_status IN (
        'REQUESTED', 'AUTHORIZED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED'))
);
