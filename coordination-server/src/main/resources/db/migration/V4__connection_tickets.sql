-- V4: single-use connection tickets for direct peer-to-peer TCP connections.
--
-- A source peer's agent asks for a ticket to connect to a target peer. The target's agent redeems
-- the ticket (authenticated with its own credential) when the source connects, which tells it the
-- server-verified identity of the source and gives it a responder proof that the source checks.
-- Only the SHA-256 hash of the ticket is stored. The responder proof is kept only until the ticket
-- is redeemed or invalidated. File contents never pass through the server.

CREATE TABLE connection_tickets (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_peer_id      UUID        NOT NULL,
    requested_by_id     UUID        NOT NULL,
    target_peer_id      UUID        NOT NULL,
    ticket_hash         VARCHAR(64) NOT NULL,
    responder_proof     VARCHAR(43),
    -- The address and port handed to the source when the ticket was issued (audit trail).
    target_host         INET        NOT NULL,
    target_port         INTEGER     NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    issued_at           TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    redeemed_at         TIMESTAMPTZ,
    outcome             VARCHAR(32),
    outcome_reported_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The requesting user must own the source peer.
    CONSTRAINT fk_connection_tickets_source_requester FOREIGN KEY (source_peer_id, requested_by_id)
        REFERENCES peers (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_tickets_target FOREIGN KEY (target_peer_id) REFERENCES peers (id) ON DELETE RESTRICT,
    CONSTRAINT uk_connection_tickets_ticket_hash UNIQUE (ticket_hash),
    CONSTRAINT ck_connection_tickets_distinct_peers CHECK (source_peer_id <> target_peer_id),
    CONSTRAINT ck_connection_tickets_ticket_hash CHECK (ticket_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_connection_tickets_proof_format CHECK (
        responder_proof IS NULL OR responder_proof ~ '^[A-Za-z0-9_-]{43}$'),
    CONSTRAINT ck_connection_tickets_port CHECK (target_port BETWEEN 1 AND 65535),
    CONSTRAINT ck_connection_tickets_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_connection_tickets_status CHECK (status IN ('ISSUED', 'REDEEMED', 'INVALIDATED')),
    CONSTRAINT ck_connection_tickets_redeemed CHECK ((status = 'REDEEMED') = (redeemed_at IS NOT NULL)),
    -- The proof exists exactly while the ticket can still be redeemed.
    CONSTRAINT ck_connection_tickets_proof CHECK ((status = 'ISSUED') = (responder_proof IS NOT NULL)),
    CONSTRAINT ck_connection_tickets_outcome CHECK (outcome IS NULL OR outcome IN (
        'SUCCEEDED', 'CONNECT_FAILED', 'TIMEOUT', 'REJECTED_BY_PEER', 'PROTOCOL_ERROR', 'PEER_IDENTITY_MISMATCH')),
    CONSTRAINT ck_connection_tickets_outcome_time CHECK ((outcome IS NULL) = (outcome_reported_at IS NULL))
);

CREATE INDEX ix_connection_tickets_source_issued ON connection_tickets (source_peer_id, issued_at DESC);
CREATE INDEX ix_connection_tickets_target_issued ON connection_tickets (target_peer_id, issued_at DESC);
