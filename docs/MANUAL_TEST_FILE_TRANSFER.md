# Manual test: direct file transfer (Phase 6)

This walkthrough shares a file from one device and has another user fetch it to their device.
The file travels directly between the two agents over mutually authenticated TLS 1.3. Part A
uses one machine and Part B two devices on a LAN.

> A same-machine test (part A) runs over loopback. It does **not** show that two devices can
> reach each other on your LAN; part B does.

The values below are placeholders. Never paste real credentials into documents or chat.

## Part A: two agents on one machine

### 1. Server and two accounts

Start PostgreSQL and the coordination server as in the README (`./mvnw clean package`, then run
the server jar with your `.env`). Then, in two browser profiles:

1. **alice** registers and adds a device `alice-laptop` with port **9401**.
2. **bob** registers and adds a device `bob-desktop` with port **9402**.
3. Keep each device's peer id and credential.

### 2. Start both agents

Each agent gets its own data and download directories, so the two "devices" don't share state:

```bash
# Terminal 1: alice-laptop (will send)
export PEERLINK_PEER_ID=<alice-laptop id>; export PEERLINK_AGENT_CREDENTIAL='<alice-laptop credential>'
java -jar peer-agent/target/peer-agent.jar --server-url=http://localhost:8080 --port=9401 \
  --data-dir=/tmp/peerlink-a/agent --download-dir=/tmp/peerlink-a/downloads
```

```bash
# Terminal 2: bob-desktop (will receive)
export PEERLINK_PEER_ID=<bob-desktop id>; export PEERLINK_AGENT_CREDENTIAL='<bob-desktop credential>'
java -jar peer-agent/target/peer-agent.jar --server-url=http://localhost:8080 --port=9402 \
  --data-dir=/tmp/peerlink-b/agent --download-dir=/tmp/peerlink-b/downloads
```

Each agent logs its TLS certificate fingerprint and `Listening for peer connections on
127.0.0.1:940x`. Both devices show as **Online** in the dashboards.

### 3. Share a file (alice)

```bash
head -c 20971520 /dev/urandom > /tmp/holiday-video.bin      # any file works

# Terminal 3, with alice-laptop's environment (as in terminal 1):
java -jar peer-agent/target/peer-agent.jar --server-url=http://localhost:8080 \
  --data-dir=/tmp/peerlink-a/agent --share=/tmp/holiday-video.bin
```

Expected: `Shared "holiday-video.bin" (20,971,520 bytes, SHA-256 …) as file …`, with exit code `0`.
The `--data-dir` must be the same one agent A uses, because that is where the agent remembers the
file's local path.

In alice's dashboard the file appears under **Files you share**. Choose **Manage access**, enter
`bob`, and click **Share**.

### 4. Request it (bob)

In bob's dashboard, under **Shared with you**, select `bob-desktop` and click **Send to device**.
Within about 5 seconds:

- **Transfers:** the transfer goes *Waiting for the device* → *Accepted* → *Transferring* →
  **Completed · SHA-256 verified**.
- **Agent B:** `Starting download of "holiday-video.bin" …` then
  `Transfer … complete: saved holiday-video.bin (… bytes, SHA-256 verified)`.
- **Agent A:** `Accepted connection from peer … (device "bob-desktop" of bob)`, `Sending transfer …`,
  then `Transfer … delivered and verified by the receiver`.

### 5. Verify the file

```bash
shasum -a 256 /tmp/holiday-video.bin /tmp/peerlink-b/downloads/holiday-video.bin
cmp /tmp/holiday-video.bin /tmp/peerlink-b/downloads/holiday-video.bin && echo identical
ls -A /tmp/peerlink-b/downloads        # only the file: no leftover .peerlink-*.part
```

Both checksums equal the one printed in step 3.

### 6. Confirm the server did not relay the file

- **Bytes received:** the server process receives only small API requests. On macOS:

  ```bash
  nettop -P -L 1 -x -J bytes_in,bytes_out -p <server pid>
  ```

  `bytes_in` stays far below the file size; in a verified run, 155,935 bytes against a
  20,971,520-byte file. On Linux, compare with `ss -tip` or `/proc/<pid>/io`.
- **Sockets:** `lsof -nP -a -p <server pid> -iTCP | grep -E ':940[12]'` finds nothing; the server
  has no connection to either agent's peer port.
- **Database:** it holds only metadata. No table has a binary column:

  ```sql
  SELECT table_name, column_name FROM information_schema.columns
  WHERE table_schema = 'public' AND data_type IN ('bytea', 'oid');           -- no rows
  SELECT e.sequence_number, e.from_status, e.to_status, p.device_name AS reported_by
  FROM transfer_events e LEFT JOIN peers p ON p.id = e.reported_by_peer_id ORDER BY 1;
  ```

  The second query shows REQUESTED (by the user), AUTHORIZED (by `alice-laptop`), then
  IN_PROGRESS and COMPLETED (by `bob-desktop`).
- **Encryption:** `sudo tcpdump -i lo0 -n -X 'tcp port 9401'` during a transfer shows only
  encrypted TLS records.

### 7. Failure cases

| Try | Expected |
|---|---|
| Request again while the first transfer is still running | 409: already being sent to that device |
| alice clicks **revoke** for bob during a large transfer (use a 1 GiB file) | It stops within about 5 s as **Failed: Access was revoked**; no partial file on B |
| bob clicks **Cancel** during a transfer | **Cancelled**; no partial file on B |
| Change the shared file after sharing (`echo x >> /tmp/holiday-video.bin`), then request it | **Declined: The file changed …**; share it again |
| Bob's device already has `holiday-video.bin` | Saved as `holiday-video (2).bin`; the existing file is untouched |
| Start agent B with `--max-file-size=1MiB` and request the 20 MiB file | **Declined: larger than the receiving device accepts**, without connecting |
| Stop agent A (Ctrl+C) during a transfer | **Failed** on B's side (connection lost); no partial file |
| Stop agent B during a transfer | Partial file deleted; **Failed: A device's agent was stopped** |
| bob (or anyone else) calls `POST /api/transfers` for a file nobody shared with him | 404 `FILE_NOT_FOUND` |
| Stop the coordination server during a large transfer, start it again after ~20 s | The sender stops sending within about 10 s (**could not confirm with the server**); nothing is saved on B |
| Suspend agent B (`kill -STOP <pid>`) during a large transfer; resume it after 40 s (`kill -CONT`) | Agent A gives up within about 30 s (**timed out**) and its upload slot is free again: another transfer from A works while B is suspended |
| Stop the coordination server just as B logs `saved …`, then start it again within 3 min | The file stays saved. B logs that the server has not confirmed it yet, then (on a later poll) that the server confirmed the completion; the dashboard shows **Completed** |
| Same, but restart agent B while the server is still down, then start the server | After restart B retries the pending report (`completion-reports-<peerId>` in `--data-dir`) and the transfer shows **Completed** |

### 8. Stop

Press Ctrl+C in both agent terminals. `lsof -nP -iTCP:9401 -iTCP:9402 -sTCP:LISTEN` then shows
nothing.

## Part B: two devices on the same LAN

1. **Server.** Run it on a machine both devices reach, for example `192.168.1.10:8080`.
   Preferably use HTTPS, for example behind a reverse proxy with `server.forward-headers-strategy`
   configured. The agent credential and the TLS fingerprints travel over this connection.
2. **Sending device.** Start its agent with `--listen-host=<its LAN IP>` and allow inbound TCP on
   `--port` (9090 by default) in its firewall. The receiving device needs no inbound rule.
3. **Server URL.** Point both agents at the server's LAN address, not `localhost`. Each dashboard
   must show the device's LAN address, and the agent logs `the server sees this device at …`.
4. **Repeat steps 3–6** on the two devices.

If something fails:

| Status | Usual cause |
|---|---|
| **Failed: could not be reached** | Firewall on the sending device, its agent listening on loopback, or different networks |
| Stays *Waiting for the device* | The receiving device's agent is not running, or cannot reach the server |
| **Failed: could not confirm with the server** | The sender lost contact with the server during the transfer |
