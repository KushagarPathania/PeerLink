# Manual test: direct peer connections (Phase 5)

This walkthrough starts two peer agents and tests a direct TCP connection between them. Part A
uses one machine. Part B repeats the test across two devices on a LAN.

> A successful same-machine test (part A) uses loopback (`127.0.0.1`). It does **not** prove that
> two devices can reach each other over the LAN; firewalls, interface binding and observed
> addresses only matter in part B.

Never paste real credentials into shared documents, tickets or chat. The values below are
placeholders.

## Part A: two agents on one machine

### 1. Start PostgreSQL and the coordination server

```bash
cp .env.example .env            # set DB_PASSWORD
set -a; source .env; set +a
docker compose up -d            # or use a native PostgreSQL (see README)
./mvnw clean package -DskipTests
java -jar coordination-server/target/coordination-server-0.1.0-SNAPSHOT.jar
```

Check the server is up: `curl http://localhost:8080/api/health` returns `"status":"UP"`.

### 2. Register two peers (Phase 4 workflow)

Use **two accounts**, for example `alice` and `bob`, in two browser profiles (or one normal and
one private window):

1. Register each account at <http://localhost:8080/register> and sign in.
2. On each dashboard, choose **Add a device**:
   - alice: device `alice-laptop`, port **9301**
   - bob: device `bob-desktop`, port **9302**
3. Copy each peer id and agent credential when they are shown (once).

One account with two devices also works: a user may connect between their own devices.

### 3. Start both agents

Use two terminals. Keep each credential in that terminal's environment only.

```bash
# Terminal 1: Peer A
export PEERLINK_PEER_ID=<alice-laptop peer id>
export PEERLINK_AGENT_CREDENTIAL='<alice-laptop credential>'
java -jar peer-agent/target/peer-agent.jar --server-url=http://localhost:8080 --port=9301
```

```bash
# Terminal 2: Peer B
export PEERLINK_PEER_ID=<bob-desktop peer id>
export PEERLINK_AGENT_CREDENTIAL='<bob-desktop credential>'
java -jar peer-agent/target/peer-agent.jar --server-url=http://localhost:8080 --port=9302
```

Each agent logs that it is listening on `127.0.0.1:930x` and that its peer is online. On one
machine, the loopback warnings are expected.

### 4. Check both peers are online

- Each dashboard shows its device as **Online**, with a recent last heartbeat.
- In alice's dashboard, **Online peers** lists `bob-desktop`, and vice versa.
- `lsof -nP -iTCP:9301 -iTCP:9302 -sTCP:LISTEN` shows both listeners bound to `127.0.0.1`.

### 5. Test a direct connection from A to B

Use a third terminal with **A's** peer id and credential. This one-shot command does not affect
the running agent A:

```bash
export PEERLINK_PEER_ID=<alice-laptop peer id>
export PEERLINK_AGENT_CREDENTIAL='<alice-laptop credential>'
java -jar peer-agent/target/peer-agent.jar --server-url=http://localhost:8080 \
  --connect-to=<bob-desktop peer id>
echo "exit code: $?"
```

### 6. Verify the handshake succeeded

- **Test terminal:** `Connection test succeeded: Connected to peer <B> at 127.0.0.1:9302 using
  protocol v1 (round trip … ms)`, with exit code `0`.
- **Agent B:** `Accepted connection from peer <A> (device "alice-laptop" of alice) at
  /127.0.0.1:<port>`, then `… closed the connection`.
- **Server log:** three audit lines:
  - `Issued connection ticket …`
  - `Connection ticket … redeemed by peer <B> …`
  - `… reported outcome SUCCEEDED`
- **Database:**

  ```sql
  SELECT status, outcome, host(target_host), target_port FROM connection_tickets ORDER BY issued_at DESC LIMIT 1;
  -- REDEEMED | SUCCEEDED | 127.0.0.1 | 9302
  ```

### 7. Confirm the server is not relaying peer traffic

- **Sockets:** the server process never holds a socket to an agent's listening port:

  ```bash
  lsof -nP -a -p <server pid> -iTCP | grep -E ':930[12]' || echo "no peer sockets on the server"
  ```

- **Agent B's log:** the remote address of the accepted connection is the initiator's address
  and ephemeral port, never the server's port 8080.
- **Optional packet capture:** run `sudo tcpdump -i lo0 -n 'tcp port 9302'` (`-i lo` on Linux)
  during step 5. Packets flow only between the two agents.
- **Server log:** it only ever contains ticket metadata, never message content.

### 8. Test invalid authorization and failures

| Test | How | Expected |
|---|---|---|
| Unknown or offline target | `--connect-to=00000000-0000-4000-8000-000000000000` | `TARGET_UNAVAILABLE`, exit `4` |
| Target agent stopped | stop agent B, wait about 60 s until it is offline, then retry step 5 | `TARGET_UNAVAILABLE` (exit `4`). Within those ~60 s the peer still looks online: `CONNECT_FAILED` (exit `4`). |
| Forged ticket | send a `HELLO` with a made-up `plt_…` ticket (script below) | `type=REJECT reason=UNAUTHORIZED` |
| Not PeerLink | `printf 'GET / HTTP/1.1\r\n\r\n' \| nc 127.0.0.1 9302` | a `REJECT` frame (`reason=MALFORMED`), then closed |
| Revoked credential | revoke `alice-laptop`'s credential on the dashboard, rerun step 5 | `CREDENTIAL_REJECTED`, exit `3`; agent A also stops with exit `3` |

Forged-ticket script (it uses a made-up ticket, so there is no secret involved):

```bash
python3 - <<'PY'
import socket, struct
s = socket.create_connection(("127.0.0.1", 9302), timeout=10)
body = b"type=HELLO\nversions=1\npeerId=7cb33355-9512-4efd-a86b-4272a1f31e96\nticket=plt_" + b"F" * 43 + b"\n"
s.sendall(b"PLNK" + struct.pack(">I", len(body)) + body)
n = struct.unpack(">I", s.recv(4))[0]
print(s.recv(n).decode())
PY
```

After any of these, a normal connection test (step 5) still succeeds. The listener keeps running.

### 9. Stop the agents and confirm a clean shutdown

Press **Ctrl+C** in each agent terminal. When an agent is stopped with SIGTERM, its exit code is
143. Then confirm both ports are free:

```bash
lsof -nP -iTCP:9301 -iTCP:9302 -sTCP:LISTEN || echo "ports released"
```

## Part B: two devices on the same LAN

1. **Start the server** on a machine both devices can reach, for example `192.168.1.10:8080`.
   Allow inbound TCP 8080 in its firewall.
2. **Start each agent with the server's LAN address** (not `localhost`), so the server observes
   the device's real LAN address:

   ```bash
   java -jar peer-agent.jar --server-url=http://192.168.1.10:8080 --port=9090 --listen-host=<this device's LAN IP>
   ```

   `--listen-host=0.0.0.0` listens on every interface. Prefer the specific LAN IP, so the agent is
   not exposed on other networks such as a VPN.
3. **Allow inbound TCP** on the agent port (9090) in each device's firewall. On macOS, allow
   `java` when prompted. On Windows, add an inbound rule.
4. **On each dashboard**, the device's address column must show its **LAN IP**, not `127.0.0.1`.
   The agent also logs `the server sees this device at …`.
5. **Run step 5 from device A** with `--connect-to=<B's peer id>`. Agent B logs the connection
   from A's LAN address.
6. **If it fails:**

   | Failure | Usual cause |
   |---|---|
   | `CONNECT_FAILED` | B's firewall blocks the port, B listens only on loopback, or the registered port differs from `--port`. The agent reports its real port with every heartbeat, so wait for one heartbeat after changing it. |
   | `TIMEOUT` | Packets are dropped: firewall, different subnet or guest Wi-Fi isolation. |
   | Address is a router or VPN address | The devices are not on the same network; this is not supported (no NAT traversal). |
