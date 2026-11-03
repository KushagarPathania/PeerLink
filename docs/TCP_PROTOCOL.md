# PeerLink Peer Protocol, version 2

This document specifies how two PeerLink peer agents connect directly, authenticate each other,
test the connection and transfer files.

The coordination server authorizes connections and records outcomes. It **never carries peer
traffic and never sees file contents**; it is not a relay or a proxy.

Version 2 replaces version 1 (Phase 5). The differences:
- the transport is mutually authenticated TLS 1.3
- frames carry a kind byte (control or data)
- file-transfer messages were added

A version-1 (plaintext) agent cannot talk to a version-2 agent.

## 1. Overview

```mermaid
sequenceDiagram
    participant B as Agent B (receiver, initiator)
    participant S as Coordination server
    participant A as Agent A (sender, listener)

    Note over A,S: A heartbeats with its TLS certificate fingerprint
    B->>S: GET /api/peers/{B}/transfers/next
    S-->>B: transfer T: file name, size, SHA-256, chunk size
    B->>S: POST /api/peers/{B}/connections {targetPeerId: A, transferId: T, initiatorCertificateSha256}
    S-->>B: ticket (single use, 60 s), A's host:port, A's certificate fingerprint, responderProof
    B->>A: TCP + TLS 1.3 (both present certificates; B pins A's fingerprint; ALPN peerlink/2)
    B->>A: "PLNK", HELLO {versions, peerId: B, ticket}
    A->>S: POST /api/peers/{A}/connections/redeem {ticket, presentedCertificateSha256}
    S-->>A: source B, B's certificate fingerprint, proof, grant: transfer T (file id, name, size, SHA-256)
    Note over A: TLS client certificate == B's fingerprint? claimed peerId == B?
    A->>B: HELLO_ACK {version 2, peerId: A, proof}
    B->>A: TRANSFER_REQUEST {T}
    A->>B: TRANSFER_OFFER {T, name, size, sha256, chunkSize}
    Note over B: offer == server metadata?
    B->>A: TRANSFER_ACCEPT {T}
    A->>B: DATA #0, DATA #1, ...  (B sends TRANSFER_ACK every 1 MiB received)
    A->>B: TRANSFER_END {T, size, sha256}
    Note over B: verify size + SHA-256, then publish the file
    B->>A: TRANSFER_COMPLETE {T, size, sha256}
    B->>S: POST /api/peers/{B}/transfers/{T}/complete {bytes, sha256}
```

A connection test (`--connect-to`) is the same up to `HELLO_ACK`, then `PING`/`PONG`/`BYE`, using
a ticket without a transfer.

## 2. Transport: TLS 1.3 with server-distributed fingerprints

- **TLS 1.3 only** (JSSE), with **ALPN `peerlink/2`** required on both sides. There is no
  plaintext mode and nothing to downgrade to.
- **Identities are ephemeral and pinned.** Each agent process generates an EC P-256 key pair and a
  self-signed X.509 certificate at start-up (`TlsIdentity`), held in memory only. Its SHA-256
  fingerprint reaches other peers only through the coordination server, which already
  authenticates every agent with its credential:
  - the listener reports its fingerprint with every heartbeat (`peers.tls_certificate_sha256`);
  - the connecting agent sends its fingerprint with the ticket request, and the ticket records it
    (`connection_tickets.initiator_certificate_sha256`).
- **The initiator pins the target** (`PinnedServerTrustManager`). The TLS handshake fails unless
  the target's certificate has exactly the fingerprint the ticket named.
- **The listener requires a client certificate** (`DeferredClientTrustManager`). The TLS handshake
  proves the client holds that certificate's private key. Which certificate is acceptable is only
  known once the ticket arrives inside the encrypted channel. The listener therefore sends the
  presented certificate's fingerprint along when it redeems the ticket; the server compares it
  with the ticket's `initiatorCertificateSha256` under the ticket's row lock and, on a mismatch,
  **refuses without consuming the ticket**, so a stolen ticket used with another key neither works
  nor burns the legitimate peer's ticket. The listener compares the fingerprint again itself
  before it sends anything except a REJECT.
- **ALPN is enforced during the handshake.** Both trust managers fail the handshake unless
  `peerlink/2` was negotiated (JSSE itself refuses a client offering only other protocols, but
  would accept one offering none); the agents check it once more afterwards.
- **The responder proof is kept as a second, independent check.** Only an agent that redeemed the
  ticket with the target's credential can return it.
- **No trust store, CA, host-name verification or "trust all".** The trust anchor is the pinned
  fingerprint.

## 3. Framing

After the TLS handshake, the initiator sends the 4-byte ASCII magic `PLNK`. Then both sides
exchange frames:

```
frame   = kind:1 length:4 payload
kind    = 'C' (0x43) control | 'D' (0x44) data
length  = unsigned 32-bit big-endian payload length
control payload = UTF-8 message, 1..4096 bytes
data payload    = index:4 (big-endian chunk number) + 1..65536 bytes of file content
```

The kind and length are validated **before any buffer is allocated**:
- an unknown kind is `MALFORMED`
- a length of 0, or a data frame with no content, is `MALFORMED`
- a length above the kind's limit is `FRAME_TOO_LARGE`
- a cut-off header or payload closes the connection

## 4. Control messages

A control payload is strict UTF-8 text: one `key=value` per line, each ending in `\n`, with
`type=<TYPE>` first. Values are 1–256 characters with no control characters. There are at most 16
fields, keys may not repeat, and each type allows only its own fields. Anything else is
`MALFORMED`, and an unknown type is `UNKNOWN_MESSAGE_TYPE`. Nothing is deserialized beyond strings,
which are then validated field by field. Java serialization is never used.

| Type | Direction | Fields |
|---|---|---|
| `HELLO` | initiator → listener | `versions` (e.g. `2`), `peerId`, `ticket` |
| `HELLO_ACK` | listener → initiator | `version`, `peerId`, `proof` |
| `REJECT` | listener → initiator | `reason` (before `HELLO_ACK`; then closed) |
| `PING` / `PONG` | initiator ↔ listener | `nonce` |
| `BYE` | either | optional `reason` |
| `TRANSFER_REQUEST` | receiver → sender | `transferId` |
| `TRANSFER_OFFER` | sender → receiver | `transferId`, `fileName`, `size`, `sha256`, `chunkSize` |
| `TRANSFER_ACCEPT` | receiver → sender | `transferId` |
| `TRANSFER_REJECT` | either, before data | `transferId`, `reason` |
| `TRANSFER_ACK` | receiver → sender | `transferId`, `bytes` (received so far) |
| `TRANSFER_END` | sender → receiver | `transferId`, `size`, `sha256` (of what was sent) |
| `TRANSFER_COMPLETE` | receiver → sender | `transferId`, `size`, `sha256` (of what was saved) |
| `TRANSFER_FAILED` | either | `transferId`, `reason` |
| `TRANSFER_CANCEL` | either | `transferId`, `reason` (`CANCELLED` or `PERMISSION_DENIED`) |

Field formats:
- ids are lower-case UUIDs
- sizes are 0–19 digits, with no sign and no leading zeros
- checksums are 64 lower-case hex digits
- `chunkSize` is 4096–65536 (see §6: the server refuses to register anything else, and both agents
  check it again before connecting or serving)

Connection `reason` values are:
`MALFORMED`, `FRAME_TOO_LARGE`, `UNKNOWN_MESSAGE_TYPE`, `UNEXPECTED_MESSAGE`,
`UNSUPPORTED_VERSION`, `UNAUTHORIZED`, `BUSY`, `UNAVAILABLE`, `TOO_MANY_MESSAGES`,
`INTERNAL_ERROR`.

Transfer `reason` values match the server's error codes: `PERMISSION_DENIED`, `PEER_BUSY`,
`FILE_UNAVAILABLE`, `FILE_CHANGED`, `FILE_TOO_LARGE`, `INSUFFICIENT_STORAGE`,
`METADATA_MISMATCH`, `CHECKSUM_MISMATCH`, `STORAGE_ERROR`, `PROTOCOL_ERROR`, `CONNECTION_LOST`,
`TIMEOUT`, `AGENT_SHUTDOWN`, `SERVER_UNAVAILABLE`, plus `CANCELLED`.

**Version negotiation:** the listener picks the highest version both sides support, or sends
`REJECT UNSUPPORTED_VERSION`. Version 2 is the only version spoken.

## 5. State machines

**Listener** (`InboundConnectionHandler`, then `UploadSession` for transfers):

```
TLS handshake (client cert required, ALPN) ─▶ "PLNK" ─▶ HELLO
  ─▶ negotiate version ─▶ redeem ticket ─▶ claimed peerId == ticket source?
  ─▶ TLS client cert == ticket's initiator fingerprint? ─▶ HELLO_ACK ─▶ ESTABLISHED
ESTABLISHED: PING→PONG (≤16) | BYE | TRANSFER_REQUEST ─▶ transfer (one per connection)
transfer:
  request.transferId == the transfer the server bound to this ticket?   no → TRANSFER_REJECT PERMISSION_DENIED
  free upload slot?                                                     no → TRANSFER_REJECT PEER_BUSY
  file in this device's share list, same size, regular file, no symlink? no → TRANSFER_REJECT FILE_UNAVAILABLE/FILE_CHANGED
  ─▶ TRANSFER_OFFER (name/size/sha256 from the server, not from the disk)
  ─▶ TRANSFER_ACCEPT | TRANSFER_REJECT
  ─▶ still authorized (asked once more)? ─▶ DATA #0..#n, never more than 8 MiB ahead of the last
     TRANSFER_ACK; before every chunk: authorization confirmed within the last 10 s?
       a checker thread asks the server every 5 s (see §7); cancelled → TRANSFER_CANCEL,
       revoked → TRANSFER_CANCEL PERMISSION_DENIED, not confirmable → TRANSFER_FAILED SERVER_UNAVAILABLE;
       if the sender does not stop within 2 s (blocked writing), the connection is closed under it
     a single write blocked longer than the idle timeout? → connection closed, TIMEOUT
     file shorter than expected?    → TRANSFER_FAILED FILE_CHANGED
  ─▶ file unchanged (size, mtime, EOF) and SHA-256 == registered? no → TRANSFER_FAILED FILE_CHANGED
  ─▶ TRANSFER_END ─▶ TRANSFER_COMPLETE (sizes and checksum must match) | TRANSFER_FAILED/CANCEL
     connection lost after TRANSFER_END → no report: only the receiver knows whether it saved the file
```

**Initiator / receiver** (`PeerConnector`, `DownloadSession`):

```
before connecting: size ≤ max file size, safe file name, enough free space? no → reject (server)
ticket(transferId) ─▶ TLS (pinned) ─▶ HELLO ─▶ HELLO_ACK (version, peerId, proof checked)
  ─▶ TRANSFER_REQUEST ─▶ TRANSFER_OFFER == server metadata?   no → TRANSFER_REJECT METADATA_MISMATCH
  ─▶ create private temp file ─▶ TRANSFER_ACCEPT
  ─▶ DATA: index == expected, length == min(chunkSize, remaining), never beyond size
     (else PROTOCOL_ERROR); TRANSFER_ACK whenever 1 MiB arrived since the last one;
     progress to server every 2 s (if cancelled or unauthorized: TRANSFER_CANCEL, stop)
  ─▶ TRANSFER_END: size and sha256 equal the server's; own byte count and SHA-256 equal too?
  ─▶ fsync ─▶ last authorization check (3 retries over ~2 s; not confirmable → discard, FAILED
     SERVER_UNAVAILABLE) ─▶ atomic rename to a unique final name          ← point of no return
  ─▶ TRANSFER_COMPLETE (best effort) ─▶ report completion to the server (see "After publication")
any failure before publication: delete the temp file, send TRANSFER_FAILED (unless the peer
already said why), report it
```

**After publication** the file is never deleted and the transfer is never reported as failed:

- If `TRANSFER_COMPLETE` cannot be sent (the sender is gone), that is logged and ignored. The
  sender, having lost the connection after `TRANSFER_END`, reports nothing either.
- The completion report is first written to a small journal in the agent's data directory
  (`completion-reports-<peerId>/`, transfer id, size and SHA-256 only). It is retried with
  exponential backoff (0.5 s, doubling up to 5 s) for 60 s: on network errors, HTTP 408/429/5xx
  and `409 CONCURRENT_MODIFICATION`. If it is still undelivered, the agent retries it on every
  transfer poll and after a restart.
- The journal entry is removed as soon as the server answers definitively: **accepted** (the
  completion endpoint is idempotent, so a repeated report for an already COMPLETED transfer with
  the same size and SHA-256 is answered 200 without recording anything again) or **refused**
  (any other 4xx, for example 409 because the server already recorded another outcome). A refusal
  is logged; success is only ever what the server confirms.
- If the server is unreachable for longer than its stall timeout (3 min), it records the transfer
  as FAILED `TIMEOUT` and later refuses the report. The file stays on the receiving device and the
  agent logs that the server recorded another outcome.

```
```

## 6. Limits and timeouts

| Setting | Default | Where |
|---|---|---|
| Control frame | 4096 bytes | protocol constant |
| Data chunk | 64 KiB as registered by the agent; **4 KiB–64 KiB enforced** by the server at registration and by both agents | protocol constants |
| Send window / ACK interval | 8 MiB / every 1 MiB received | protocol constants |
| Max file size | 1 GiB | `--max-file-size`, both sender (`--share`) and receiver |
| Concurrent transfers | 2 uploads + 2 downloads per agent | `--max-transfers` |
| Concurrent connections | 8 | `--max-connections` |
| Connect / handshake (TLS + PeerLink) | 5 s / 15 s total | agent |
| Idle (per message after the handshake) | 30 s | agent |
| Single blocked write (listener) | 30 s (the idle timeout), then the connection is closed | agent |
| Single blocked write (connecting side) | 15 s (the handshake timeout), then the connection is closed | agent |
| Sender authorization re-check | every 5 s on a separate thread; data only while the last confirmation is < 10 s old (fail closed) | agent |
| Stop grace | 2 s for a stopped sender to finish, then its connection is closed | agent |
| Final authorization check (receiver) | 4 attempts over ~2 s; then the file is discarded | agent |
| Completion report | retried for 60 s with backoff, then on every poll and after restart until the server answers | agent |
| Receiver progress report | every 2 s | agent |
| Ticket lifetime | 60 s | `PEER_CONNECTION_TICKET_TTL` |
| Unclaimed request / stalled transfer | 30 min / 3 min | `PEER_TRANSFER_REQUEST_TTL` / `PEER_TRANSFER_STALL_TIMEOUT` |

**Memory use per transfer is bounded.** The sender reuses one chunk-sized buffer. The receiver
holds at most one chunk at a time. The SHA-256 is computed incrementally. Files are never loaded
whole, and declared sizes never drive allocations.

## 7. Authorization

| Step | Check |
|---|---|
| Request (user) | The receiving device is the requester's own, and the requester owns the file or holds an active (unrevoked, unexpired) permission. The file is still shared, both accounts are enabled, the sender is online, and no duplicate transfer is running. |
| Ticket issue (receiver's agent) | The receiver is the transfer's receiving peer, the target is its sending peer, the transfer is REQUESTED, no other unexpired ticket is out for it, and the requester is still authorized. |
| Redeem (sender's agent) | All the Phase 5 ticket checks (target, unused, unexpired, source credential unchanged), plus: the presented TLS client certificate is the ticket's initiator certificate (a mismatch is refused without consuming the ticket), and the transfer is still REQUESTED and still authorized. The transfer then becomes AUTHORIZED. |
| Serve (sender) | The requested transfer is exactly the one the server bound to the ticket, and the file is in this device's own share list (id → path). The receiver can never name a path. |
| During (both) | The sender asks the server every 5 s and sends data only while the last confirmation is under 10 s old; if the server cannot be asked, the sender stops (`SERVER_UNAVAILABLE`). The receiver's progress reports are answered with the current status. Revocation or cancellation stops the transfer, even a sender blocked in a write. |
| Before publication (receiver) | A last progress report at full size must confirm the transfer is still active and authorized. If the server cannot be reached after retries, the file is not published. |
| Complete (receiver) | Only the receiving peer may complete, and its reported size and SHA-256 must equal the registered file's (otherwise FAILED `CHECKSUM_MISMATCH`). Authorization is checked again: if access was revoked meanwhile, the transfer is FAILED `PERMISSION_DENIED` (409). Repeating an accepted report is idempotent (200, nothing recorded again); completing a transfer in any other finished state is a 409 conflict. |

## 8. Security properties and limitations

**Provided (and covered by tests):**
- Confidentiality and integrity of all peer traffic, control and data alike, through TLS 1.3.
- Mutual authentication. The initiator pins the target's certificate. The listener accepts only
  the certificate bound to the ticket, and only an initiator holding a valid, unused ticket for
  this exact pair of peers.
- No replay. A ticket is single-use with atomic redemption, and a ticket bound to one transfer
  cannot fetch another.
- No downgrade. TLS 1.3 and ALPN are required, and there is no plaintext version.
- No arbitrary file access. Only explicitly shared files, addressed by server-authorized id, are
  ever served.
- No path traversal and no overwriting. Names are validated, writes go only into the download
  directory, the final name is reserved by exclusive creation, and symbolic links are never
  followed.
- Integrity before publication. Sequence, exact sizes and SHA-256 are verified, and the file is
  published only after verification.
- Bounded resources: frame sizes, connections, transfers, buffers and deadlines, including a
  deadline on every blocking write (a peer that stops reading cannot hold a transfer slot).

**Limitations:**
- **Trust is anchored in the coordination server.** Fingerprints and tickets come from it over the
  agent↔server channel.
  - If that channel is plain HTTP on an untrusted network, an attacker who can intercept it could
    substitute fingerprints and steal agent credentials. **Use HTTPS in production.**
  - A malicious or compromised coordination server could do the same. There is no end-to-end
    trust independent of the server.
- **SHA-256 detects corruption; it does not authenticate.** Authenticity comes from TLS and the
  ticket, not from the checksum.
- **Interrupted transfers are not resumed.** They fail, and the partial file is deleted.
- **There is no NAT traversal or relay.** Peers must reach each other directly.
- **Some filesystem protections cannot be guaranteed portably:**
  - Another local user with write access to the download directory itself could still interfere
    with files there; the agent does not defend against hostile local users.
  - Windows-reserved names (`CON`, `NUL`, …) are not rejected.
- **No rate limiting** on ticket issuance or transfer requests, and no retention limit for
  `connection_tickets` and `transfer_events`.
