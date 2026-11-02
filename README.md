# PeerLink: P2P File Sharing

PeerLink lets registered users discover online peers on their local network and send files
directly between devices over Java sockets. A Spring Boot **coordination server** handles
accounts, discovery and authorization. A lightweight Java **peer agent** on each device does the
actual file transfer. File contents never pass through the server.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

> **Status: Phase 5 (direct peer connections).** Done so far:
> - Phase 1: project setup, configuration, Flyway, a health endpoint and the agent entry point.
> - Phase 2: the PostgreSQL schema, JPA entities, repositories and DTOs.
> - Phase 3: registration, sign-in, sign-out and an account dashboard, in the browser and through
>   a session-based JSON API.
> - Phase 4: device (peer) registration with per-device agent credentials, agent heartbeats,
>   online/offline tracking and discovery of other users' online peers.
> - Phase 5: direct, server-authorized TCP connections between peer agents, using a small
>   versioned protocol with handshake, mutual identity check and connection test.
>
> **No file data is transferred yet.** File sharing and transfers come in Phase 6.

## Project layout

```
peerlink/
├── pom.xml                  # Parent/aggregator POM (Spring Boot 3.5 dependency management)
├── mvnw, mvnw.cmd, .mvn/    # Maven Wrapper: no local Maven install needed
├── coordination-server/     # Spring Boot web app (REST API, Thymeleaf UI, PostgreSQL)
├── peer-agent/              # Plain Java 21 agent (sockets, transfers)
├── docs/ARCHITECTURE.md
├── docker-compose.yml       # Optional local PostgreSQL
└── .env.example             # Configuration template (no real secrets)
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 or newer | The code targets Java 21 (`--release 21`) |
| PostgreSQL | 14+ (16 recommended) | Native install **or** Docker |
| Docker | optional | Only for `docker-compose.yml` and the full-startup integration test |
| Maven | not required | Use the included `./mvnw` |

## 1. Configure the environment

```bash
cp .env.example .env
# edit .env and set DB_PASSWORD (and anything else you want to change)
```

Spring Boot does **not** read `.env` files on its own. Export the values into your shell before
you start the server:

```bash
set -a; source .env; set +a
```

| Variable | Used by | Default |
|---|---|---|
| `DB_URL` | server | `jdbc:postgresql://localhost:5432/peerlink` |
| `DB_USERNAME` | server | `peerlink` |
| `DB_PASSWORD` | server | *(none; required)* |
| `SERVER_PORT` | server | `8080` |
| `SESSION_COOKIE_SECURE` | server | `false`; **set to `true` in production (HTTPS)** |
| `SESSION_TIMEOUT` | server | `30m` |
| `PEER_HEARTBEAT_INTERVAL` | server | `15s` (interval agents are told to use) |
| `PEER_STALE_TIMEOUT` | server | `60s` (must be ≥ 2 × heartbeat interval) |
| `PEER_SWEEP_INTERVAL` | server | `15s` (how often stale peers are marked OFFLINE) |
| `PEER_SWEEP_ENABLED` | server | `true` |
| `PEER_CONNECTION_TICKET_TTL` | server | `60s` (lifetime of a direct-connection ticket, 5s–10m) |
| `PEERLINK_SERVER_URL` | agent | `http://localhost:8080` |
| `PEERLINK_AGENT_PORT` | agent | `9090` |
| `PEERLINK_DOWNLOAD_DIR` | agent | `~/PeerLink/downloads` |
| `PEERLINK_PEER_ID` | agent | *(none)*: the registered peer to keep online |
| `PEERLINK_AGENT_CREDENTIAL` | agent | *(none)*: that peer's agent credential (secret) |
| `PEERLINK_CREDENTIAL_FILE` | agent | *(none)*: file containing the credential, instead of the variable above |
| `PEERLINK_HEARTBEAT_INTERVAL` | agent | `15` seconds |
| `PEERLINK_AGENT_PORT` / `--port` | agent | `9090`: TCP port the peer listener binds to |
| `PEERLINK_LISTEN_HOST` / `--listen-host` | agent | `127.0.0.1` (this machine only); a LAN IP or `0.0.0.0` for other devices |
| `PEERLINK_LISTEN_ENABLED` / `--listen` | agent | `true`: accept direct connections when a peer is configured |
| `PEERLINK_MAX_CONNECTIONS` / `--max-connections` | agent | `8` simultaneous incoming connections (1–64) |

## 2. Set up PostgreSQL

The server needs an empty database and a user that owns it. **Flyway creates all tables
automatically** at startup, so you never run schema SQL by hand.

**Option A: Docker** (uses the credentials from `.env`):

```bash
docker compose up -d
```

**Option B: native PostgreSQL** (for example `brew install postgresql@16 && brew services start postgresql@16`):

```bash
psql postgres
```
```sql
CREATE USER peerlink WITH PASSWORD 'the-same-value-as-DB_PASSWORD';
CREATE DATABASE peerlink OWNER peerlink;
```

## Database

The server stores **metadata only**: users, peer devices, file names, sizes and SHA-256
checksums, sharing permissions, and transfer history. File contents never reach the server or the
database. They move directly between peer agents. No local file paths are stored either.

| Table | Holds |
|---|---|
| `users` | Accounts (BCrypt password hash only; username and email unique, stored lower-case) |
| `peers` | Each user's devices: observed IP (`inet`), port, last heartbeat |
| `shared_files` | Metadata for files a user chose to share from one of their devices |
| `share_permissions` | Which users may request which file (revocable, optional expiry) |
| `file_transfers` | Transfer requests, status and progress, checksum verification result |
| `transfer_events` | Append-only audit trail of every transfer status change |

The ER diagram, relationships, constraints and deletion policy are in
[docs/ARCHITECTURE.md § 6](docs/ARCHITECTURE.md#6-data-model).

**Migrations.** Flyway applies `coordination-server/src/main/resources/db/migration/V*.sql`
automatically when the server starts. Hibernate only *validates* the schema; it never changes
it. To change the schema, add a new `V<next>__description.sql`. Never edit a migration that has
already been applied.

**Inspecting the tables:**

```bash
# Docker (docker compose up -d):
docker exec -it peerlink-postgres psql -U peerlink -d peerlink
# Native install:
psql -h localhost -U peerlink -d peerlink
```
```sql
\dt                                   -- list tables
\d file_transfers                     -- columns, indexes, constraints, foreign keys
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT username, email, enabled, created_at FROM users;
```

## 3. Build and test

```bash
./mvnw clean verify
```

This compiles both modules and runs every test.

The database tests run against a throwaway PostgreSQL 16 container (Testcontainers). They are
`PeerLinkServerApplicationTests`, everything in `repository/`, and the authentication tests
`AuthApiIntegrationTests` and `WebAuthIntegrationTests`. Together they cover the Flyway
migrations, Hibernate schema validation, repository queries, every database constraint, and
registration, sign-in, sign-out, CSRF and access rules through the real security filters. These tests are
**skipped automatically when Docker isn't running**. The first run downloads the
`postgres:16-alpine` image.

## 4. Run the coordination server

```bash
set -a; source .env; set +a
./mvnw -pl coordination-server spring-boot:run
# or run the packaged jar:
java -jar coordination-server/target/coordination-server-0.1.0-SNAPSHOT.jar
```

- Landing page: <http://localhost:8080/>
- Create an account: <http://localhost:8080/register>
- Sign in: <http://localhost:8080/login> (with your username or your email)
- Account dashboard: <http://localhost:8080/dashboard> (requires sign-in)
- Health check: <http://localhost:8080/api/health>

```bash
curl http://localhost:8080/api/health
# {"status":"UP","service":"peerlink-coordination-server","database":"UP","timestamp":"..."}
```

The health endpoint returns **HTTP 200** when the database is reachable and **HTTP 503**
otherwise.

## Accounts and authentication

### Features

- **Registration** at `/register` or `POST /api/auth/register`.
  - Fields: username, email, display name, password and password confirmation.
  - Passwords need 12–72 characters, and no more than 72 bytes when UTF-8 encoded (BCrypt's
    limit).
  - Usernames and emails are unique regardless of case.
- **Sign-in** with your username *or* your email, at `/login` or `POST /api/auth/login`.
- **Sign-out** at `/logout` or `POST /api/auth/logout`. Either one invalidates the session on the
  server.
- An **account dashboard** at `/dashboard`.

### How it works

- **Sessions, not JWTs.** After a successful sign-in, Spring Security stores the authentication in
  the server-side HTTP session. The browser holds only the `PEERLINK_SESSION` cookie, which is
  `HttpOnly`, so JavaScript cannot read it. No password or reusable credential is ever stored in
  the browser.
- **Passwords** are hashed with BCrypt (Spring Security's `BCryptPasswordEncoder`) and verified by
  Spring Security's `DaoAuthenticationProvider`. Plaintext passwords are never stored or logged,
  and the hash never appears in a response.
- **Session fixation protection:** the session id changes at sign-in, and the CSRF token is
  replaced.
- **No account disclosure at sign-in.** A wrong password and an unknown account get the same
  message: "Invalid username/email or password." Only someone who supplies the *correct* password
  for a disabled account is told that it is disabled.
- **Protected by default.** Every route except `/`, `/login`, `/register`, `/error`,
  `/api/health`, the auth endpoints and the static assets requires sign-in. Anonymous browser
  requests are redirected to `/login`. Anonymous API requests get **401** JSON, never a redirect.
  Signed-in users without permission get **403** JSON.
- **The current user always comes from the session.** No endpoint accepts a user id to decide who
  the caller is.

### CSRF

CSRF protection is on for every state-changing request (`POST`, `PUT`, `PATCH`, `DELETE`),
including sign-in and registration.

- **Thymeleaf forms** include the token automatically as a hidden `_csrf` field.
- **JavaScript on signed-in pages** reads the token from the `<meta name="_csrf">` and
  `<meta name="_csrf_header">` tags and sends it in that header with `fetch`.
- **API clients** call `GET /api/auth/csrf` to get `{headerName, parameterName, token}`, then send
  `X-CSRF-TOKEN: <token>` with the same session cookie.

The token is rotated at sign-in, so fetch a new one after logging in. Requests with a missing or
wrong token get `403` with `"code": "CSRF_TOKEN_INVALID"`.

### Authentication API

| Method and path | Auth | Success | Notes |
|---|---|---|---|
| `GET /api/auth/csrf` | none | 200 | CSRF token for the current session |
| `POST /api/auth/register` | none (CSRF) | 201 + profile | 400 `VALIDATION_FAILED`, 409 `USERNAME_TAKEN` / `EMAIL_TAKEN` |
| `POST /api/auth/login` | none (CSRF) | 200 + profile, new session cookie | 401 `INVALID_CREDENTIALS` / `ACCOUNT_DISABLED` |
| `GET /api/auth/me` | session | 200 + own profile | 401 `UNAUTHENTICATED` |
| `POST /api/auth/logout` | CSRF | 204 | Invalidates the session |

Errors use RFC 9457 problem details, plus a stable `code` and, for validation errors, a list of
`errors` like `[{"field","message"}]`. Responses never include stack traces, SQL, submitted
values, password hashes or session ids.

Try it with `curl` (a cookie jar plays the browser's role):

```bash
B=http://localhost:8080; J=cookies.txt
csrf() { curl -s -b $J -c $J $B/api/auth/csrf | sed -E 's/.*"token":"([^"]+)".*/\1/'; }

curl -s -b $J -c $J -H "X-CSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","displayName":"Demo User",
       "password":"correct-horse-battery","confirmPassword":"correct-horse-battery"}' \
  $B/api/auth/register                                        # 201

curl -s -b $J -c $J -H "X-CSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -d '{"identifier":"demo","password":"correct-horse-battery"}' $B/api/auth/login   # 200

curl -s -b $J $B/api/auth/me                                  # 200, your profile
curl -s -b $J -c $J -X POST -H "X-CSRF-TOKEN: $(csrf)" $B/api/auth/logout -w '%{http_code}\n'  # 204
curl -s -b $J $B/api/auth/me                                  # 401
```

### Local development vs. production

| Setting | Local default | Production |
|---|---|---|
| Transport | plain HTTP on `localhost` | **HTTPS only** (TLS at the app or a reverse proxy) |
| `SESSION_COOKIE_SECURE` | `false` | **`true`**, so the cookie is never sent over HTTP |
| Cookie `HttpOnly` / `SameSite` | `true` / `Lax` | same |
| Session timeout | `SESSION_TIMEOUT=30m` | choose to suit; idle sessions expire on the server |
| Behind a reverse proxy | n/a | set `server.forward-headers-strategy=native` (or `framework`) so redirects and secure cookies use `https` |

Sessions live in the server's memory, so restarting the server signs everyone out. Running
several server instances would need shared sessions (for example Spring Session with JDBC) or
sticky load balancing.

## Peers and presence

A **peer** is one of your devices running the PeerLink agent. You register it while signed in,
and its agent then keeps it online by sending heartbeats.

### Registering a device and connecting its agent

1. Sign in, open the **dashboard**, choose **Add a device**, and enter a device name and the
   agent's TCP port (default 9090).
2. PeerLink shows an **agent credential** (`plk_…`) **once**. The server keeps only its SHA-256
   hash, so it can never show the credential again. If it is lost, issue a new one.
3. On that device, start the agent with the peer id and the credential:

   ```bash
   export PEERLINK_PEER_ID=<peer id from the dashboard>
   export PEERLINK_AGENT_CREDENTIAL='<credential from the dashboard>'
   # or: write the credential to a file readable only by you, then use --credential-file=<path>
   java -jar peer-agent.jar --server-url=http://<server LAN address>:8080
   ```

The credential is never accepted as a command-line flag, because other users of the machine can
see command lines. The credential is not your password. It is a random device secret: it can
only send heartbeats for that one device, and you can rotate or revoke it from the dashboard at
any time.

### Heartbeats and online status

- The agent sends `POST /api/peers/{peerId}/heartbeat` every `PEERLINK_HEARTBEAT_INTERVAL` seconds
  (default 15), using the header `Authorization: Bearer <credential>`.
- The server records the heartbeat time from **its own clock** and the device's address from
  **the connection it observes**. Any timestamp, address or other data in the request is ignored.
- **A peer is online exactly while its last heartbeat is at most `PEER_STALE_TIMEOUT` old**
  (default 60 s). Every read applies this rule directly, so a stale peer is reported OFFLINE even
  before the database row changes. A background sweep then marks stale rows OFFLINE every
  `PEER_SWEEP_INTERVAL`, updating only rows that are actually stale.
- **A device that loses its connection still appears online until its last heartbeat expires**,
  which takes up to the stale timeout.
- **Online is not the same as reachable.** It means "the agent recently talked to the server",
  not "a direct connection to it will succeed". See [Networking scope](#networking-scope).

### Peer API

All endpoints except the heartbeat need a signed-in session, and POST/DELETE requests need the
CSRF token, as described in [Authentication API](#authentication-api).

| Method and path | Who | Result |
|---|---|---|
| `POST /api/peers` `{deviceName, listeningPort}` | signed-in user | 201 + peer + one-time `agentCredential` |
| `GET /api/peers/mine` | signed-in user | your devices (status, port, last heartbeat, observed address) |
| `GET /api/peers/{peerId}` | owner | that device; **404** for other users' devices, the same as for non-existent ones |
| `GET /api/peers/online?page=0&size=20` | signed-in user | other users' online peers (paged, size ≤ 100) |
| `POST /api/peers/{peerId}/heartbeat` | that peer's **agent** (bearer credential) | 200 `{status, serverTime, observedAddress, heartbeatIntervalSeconds, staleTimeoutSeconds}` |
| `POST /api/peers/{peerId}/credential` | owner | 200 + new credential (the old one stops working) |
| `DELETE /api/peers/{peerId}/credential` | owner | 204; the agent is disconnected and the peer goes OFFLINE |

- The owner of a peer is always the signed-in user. An address, owner or status in the request
  body is ignored.
- Discovery shows the owner's username and display name, the device name and the last heartbeat.
  It does **not** show email addresses, IP addresses or ports, and it leaves out your own devices,
  stale peers, offline peers and disabled accounts.
- Heartbeats from a browser session are rejected (403). Only the agent, using its own connection,
  may report the device's address.

Example, continuing the `curl` session from the authentication section (`$B`, `$J`, `csrf`):

```bash
curl -s -b $J -c $J -H "X-CSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -d '{"deviceName":"work-laptop","listeningPort":9090}' $B/api/peers      # 201, note peer.id + credential

curl -s -b $J $B/api/peers/mine                                         # your devices
curl -s -b $J "$B/api/peers/online?size=20"                             # other users' online peers

# What the agent sends (no cookie, no CSRF token needed for bearer requests):
curl -s -X POST -H "Authorization: Bearer $PEERLINK_AGENT_CREDENTIAL" \
  $B/api/peers/$PEERLINK_PEER_ID/heartbeat                               # 200, status ONLINE

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -b $J -H "X-CSRF-TOKEN: $(csrf)" \
  $B/api/peers/$PEERLINK_PEER_ID/credential                              # 204, credential revoked
```

## 5. Run the peer agent

```bash
./mvnw -pl peer-agent package          # builds peer-agent/target/peer-agent.jar
java -jar peer-agent/target/peer-agent.jar --help
```

A command-line flag overrides the matching environment variable, which overrides the default.

- **Without a peer id:** the agent validates its configuration, checks that the coordination
  server is healthy, and exits. This is the original behaviour.
- **With `PEERLINK_PEER_ID` and a credential:** the agent keeps that peer online until stopped,
  and listens for authorized direct connections from other peers (see
  [Direct peer connections](#direct-peer-connections)).
  - Each request has a 5 s connection timeout and a 5 s response timeout.
  - Network failures are retried with exponential backoff (1 s, 2 s, 4 s, …, capped at 60 s), so
    the agent rides out server restarts.
  - If the server rejects the credential (revoked, rotated, or the account disabled), the agent
    stops instead of retrying forever.
  - Ctrl+C or SIGTERM stops the heartbeat thread and the listener cleanly, releasing the port.
  - A failed peer connection never stops heartbeats.
  - The agent logs the address the server sees it at, and warns if that is a loopback address.
  - The credential is never logged.

| Exit code | Meaning |
|---|---|
| `0` | Server healthy (no peer configured), or heartbeats stopped normally |
| `1` | Server unreachable or unhealthy (no peer configured) |
| `2` | Invalid configuration |
| `3` | The server rejected the agent credential |
| `4` | `--connect-to`: the connection test failed |
| `5` | The peer listener could not start (for example, the port is in use) |
| `143` | Stopped by SIGTERM (standard for signal termination) |

## Direct peer connections

Phase 5 lets two peer agents open a **direct TCP connection** and verify each other. **It does not
transfer files yet**; that is Phase 6. The coordination server authorizes each connection but
never carries any of its traffic.

**How a connection is authorized:**

1. The connecting agent (A) asks the server for a **connection ticket** for the target peer (B).
   It authenticates with its own agent credential.
2. If B is online, the server returns a single-use ticket that expires after 60 s, together with
   B's observed address and port. This is the only place a peer's address is ever revealed, and
   only to an authorized requester.
3. A connects to B and presents the ticket.
4. B **redeems** the ticket with the server, using its own credential, which tells it who A
   really is. Then B returns a proof that only the genuine B could have obtained, so A knows it
   reached the right peer.
5. A and B exchange a `PING`/`PONG` connection test and close the connection.

Agent credentials never leave their own agent. Tickets and proofs are never logged.

**Listening:**
- A running agent with a configured peer listens on `--port` (default 9090).
- The default bind address is **`127.0.0.1`**, so by default only programs on the same machine
  can connect.
- To accept peers on other devices, start the agent with `--listen-host=<this device's LAN IP>`
  (recommended) or `--listen-host=0.0.0.0` (all interfaces), and allow that TCP port in the
  firewall.
- The agent reports its listening port with each heartbeat, so the server always hands out the
  right port.

**Testing a connection:** run a one-shot command with the source device's peer id and credential:

```bash
export PEERLINK_PEER_ID=<this device's peer id>
export PEERLINK_AGENT_CREDENTIAL='<this device's credential>'
java -jar peer-agent/target/peer-agent.jar --server-url=http://<server>:8080 --connect-to=<target peer id>
```

It runs in its own process, so it never delays the heartbeats of the running agent. It makes one
attempt (no retries), prints the result, reports the outcome to the server, and exits:
- `0`: connected
- `4`: the connection failed, with the reason
- `3`: this device's credential was rejected

**More detail:**
- [docs/TCP_PROTOCOL.md](docs/TCP_PROTOCOL.md): protocol and security limitations, notably that
  there is **no TLS yet**
- [docs/MANUAL_TEST_PEER_CONNECTIONS.md](docs/MANUAL_TEST_PEER_CONNECTIONS.md): a step-by-step
  test with two agents, on one machine and across a LAN

Agent-only endpoints (Bearer agent credential, each only for the agent's own peer):

| Method and path | Result |
|---|---|
| `POST /api/peers/{peerId}/connections` `{targetPeerId}` | 201 ticket, target host and port, responder proof, expiry; 404 if the target is not available |
| `POST /api/peers/{peerId}/connections/redeem` `{ticket}` | 200 source peer id, device and owner, plus the proof; 403 `CONNECTION_TICKET_INVALID` for unknown, expired, used, revoked or misdirected tickets |
| `POST /api/peers/{peerId}/connections/{ticketId}/outcome` `{outcome}` | 204; reported once, by the source only |

## Networking scope

A peer's address is whatever address the server **observes** on the agent's heartbeat
connection. The server does not trust addresses that clients report about themselves, and it
does not trust `X-Forwarded-For` unless you explicitly configure
`server.forward-headers-strategy` for a reverse proxy you control. This has consequences:

- If the agent reaches the server through `localhost`, the observed address is `127.0.0.1`, which
  no other device can use. Start the agent with the server's LAN address instead; the agent warns
  when this happens.
- Behind NAT, a VPN or a proxy, the observed address may be the router's, the VPN's or the
  proxy's, not one that other peers can reach.
- A device with several network interfaces is recorded at the address it used to reach the
  server.

PeerLink currently targets peers on the **same LAN**. Each device's agent must listen on a LAN
address (`--listen-host`, see above), and its firewall must allow inbound TCP on the agent port
(default 9090). A connection test between two agents on one machine succeeds over loopback, but
that does not show the devices can reach each other on the LAN. Peers on different networks need NAT traversal, a relay,
port forwarding or a VPN, which are out of scope for now. See the architecture document.

## Tech stack

Java 21 · Spring Boot 3.5 (Web, Security, Data JPA, Validation, Thymeleaf) · Hibernate ·
PostgreSQL · Flyway · Bootstrap 5 · Java sockets / `ExecutorService` · JUnit 5 · Mockito ·
Testcontainers · Maven
